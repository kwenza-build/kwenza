package dev.kwenza.resolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Talks to a Maven-Central-layout repository (remote HTTP + a local ~/.m2-compatible cache).
 * GAV to path/URL mapping matches Maven exactly: {group//.//}/artifact/version/artifact-version.ext
 */
public final class MavenRepository {

    public static final String CENTRAL_BASE_URL = "https://repo.maven.apache.org/maven2";

    private final String baseUrl;
    private final Path localRepo;
    private final HttpClient client;

    public MavenRepository(String baseUrl, Path localRepo) {
        this.baseUrl = baseUrl;
        this.localRepo = localRepo;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static MavenRepository central(Path localRepo) {
        return new MavenRepository(CENTRAL_BASE_URL, localRepo);
    }

    private static String groupPath(String group) {
        return group.replace('.', '/');
    }

    public Path artifactPath(String group, String artifact, String version, String extension) {
        return localRepo.resolve(groupPath(group)).resolve(artifact).resolve(version)
                .resolve(artifact + "-" + version + "." + extension);
    }

    private String artifactUrl(String group, String artifact, String version, String extension) {
        return baseUrl + "/" + groupPath(group) + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + "." + extension;
    }

    /** Fetches text content (POM/checksum), preferring the local cache when present. */
    public CompletableFuture<String> fetchTextAsync(String group, String artifact, String version, String extension) {
        Path cached = artifactPath(group, artifact, version, extension);
        if (Files.exists(cached)) {
            try {
                return CompletableFuture.completedFuture(Files.readString(cached));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return fetchRemoteTextAsync(group, artifact, version, extension);
    }

    /** Always goes to the repository, never the local cache, then refreshes the cached copy. */
    private CompletableFuture<String> fetchRemoteTextAsync(String group, String artifact, String version, String extension) {
        Path cached = artifactPath(group, artifact, version, extension);
        String url = artifactUrl(group, artifact, version, extension);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new ResolutionException("Failed to fetch " + url + " (HTTP " + response.statusCode() + ")");
                    }
                    String body = response.body();
                    writeToCache(cached, body.getBytes(StandardCharsets.UTF_8));
                    return body;
                });
    }

    /**
     * Downloads a binary artifact (normally a jar), verifying it against the repository's published
     * .sha1. Fails closed: a missing, unfetchable or blank .sha1 is refused exactly like a mismatch,
     * and nothing unverified is ever written to the local cache. A jar already in the local cache is
     * returned as-is - callers that need it verified use {@link #fetchJarVerifiedAsync} or check it
     * against a lock themselves.
     */
    public CompletableFuture<Path> fetchJarAsync(String group, String artifact, String version) {
        Path cached = artifactPath(group, artifact, version, "jar");
        if (Files.exists(cached)) {
            return CompletableFuture.completedFuture(cached);
        }
        String gav = group + ":" + artifact + ":" + version;
        String jarUrl = artifactUrl(group, artifact, version, "jar");
        CompletableFuture<String> sha1Future = fetchExpectedSha1Async(group, artifact, version);
        HttpRequest request = HttpRequest.newBuilder(URI.create(jarUrl)).GET().build();
        CompletableFuture<byte[]> bodyFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new ResolutionException("Failed to fetch " + jarUrl + " (HTTP " + response.statusCode() + ")");
                    }
                    return response.body();
                });
        return bodyFuture.thenCombine(sha1Future, (bytes, expected) -> {
            String actual = sha1Hex(bytes);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new ResolutionException("Checksum mismatch for " + gav
                        + " (expected " + expected + ", got " + actual + ")");
            }
            writeToCache(cached, bytes);
            return cached;
        });
    }

    /**
     * Like {@link #fetchJarAsync}, but a jar already in the local cache is also checked against the
     * repository's published .sha1 - never the .sha1 cached beside it, which anyone able to alter
     * the jar could alter too. Costs one small request per cache hit, so it's for jars nothing else
     * (like a lock's sha256) already vouches for.
     */
    public CompletableFuture<Path> fetchJarVerifiedAsync(String group, String artifact, String version) {
        Path cached = artifactPath(group, artifact, version, "jar");
        if (!Files.exists(cached)) {
            return fetchJarAsync(group, artifact, version);
        }
        String gav = group + ":" + artifact + ":" + version;
        return fetchExpectedSha1Async(group, artifact, version).thenApply(expected -> {
            String actual;
            try {
                actual = sha1Hex(Files.readAllBytes(cached));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (!actual.equalsIgnoreCase(expected)) {
                throw new ResolutionException("Checksum mismatch for " + gav + ": " + cached + " has sha1 " + actual
                        + " but the repository publishes " + expected + ". The cached jar has been altered;"
                        + " delete it so kwenza re-downloads and re-verifies it.");
            }
            return cached;
        });
    }

    /** The jar's published sha1 hex, failing closed if it's missing, unfetchable or blank. */
    private CompletableFuture<String> fetchExpectedSha1Async(String group, String artifact, String version) {
        String gav = group + ":" + artifact + ":" + version;
        String sha1Url = artifactUrl(group, artifact, version, "jar.sha1");
        return fetchRemoteTextAsync(group, artifact, version, "jar.sha1").handle((sha1, ex) -> {
            if (ex != null) {
                Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                throw new ResolutionException("Refusing to use " + gav + ": its checksum " + sha1Url
                        + " couldn't be fetched (" + cause.getMessage() + "), so the jar can't be verified", cause);
            }
            if (sha1 == null || sha1.isBlank()) {
                throw new ResolutionException("Refusing to use " + gav + ": " + sha1Url
                        + " is empty, so the jar can't be verified");
            }
            return sha1.trim().split("\\s+")[0];
        });
    }

    private static void writeToCache(Path path, byte[] bytes) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha1Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
