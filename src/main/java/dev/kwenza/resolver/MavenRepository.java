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

    public Path localRepoRoot() {
        return localRepo;
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

    /** Downloads a binary artifact (normally a jar), verifying it against Central's published .sha1 when available. */
    public CompletableFuture<Path> fetchJarAsync(String group, String artifact, String version) {
        Path cached = artifactPath(group, artifact, version, "jar");
        if (Files.exists(cached)) {
            return CompletableFuture.completedFuture(cached);
        }
        String jarUrl = artifactUrl(group, artifact, version, "jar");
        CompletableFuture<String> sha1Future = fetchTextAsync(group, artifact, version, "jar.sha1")
                .exceptionally(ex -> null);
        HttpRequest request = HttpRequest.newBuilder(URI.create(jarUrl)).GET().build();
        CompletableFuture<byte[]> bodyFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new ResolutionException("Failed to fetch " + jarUrl + " (HTTP " + response.statusCode() + ")");
                    }
                    return response.body();
                });
        return bodyFuture.thenCombine(sha1Future, (bytes, expectedSha1) -> {
            if (expectedSha1 != null && !expectedSha1.isBlank()) {
                String actual = sha1Hex(bytes);
                String expected = expectedSha1.trim().split("\\s+")[0];
                if (!actual.equalsIgnoreCase(expected)) {
                    throw new ResolutionException("Checksum mismatch for " + group + ":" + artifact + ":" + version
                            + " (expected " + expected + ", got " + actual + ")");
                }
            }
            writeToCache(cached, bytes);
            return cached;
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
