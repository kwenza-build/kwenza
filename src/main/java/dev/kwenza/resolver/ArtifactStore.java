package dev.kwenza.resolver;

import dev.kwenza.context.Result;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

/**
 * Every artifact kwenza uses comes through here: POMs from the local cache or the repository, and
 * jars only through {@link #jar}, which applies the one trust rule. There is no way to get a jar
 * without saying what vouches for it, so no caller can pick an unverified path by mistake (see
 * AGENTS.md, "Checksums fail closed").
 *
 * <ul>
 *   <li>{@link Expectation.Pinned}: kwenza.lock's sha256 vouches for it. A cached jar is checked
 *       against the pin locally, with no network; a missing one is downloaded, checked against
 *       the repository's .sha1 and then the pin.
 *   <li>{@link Expectation.Published}: only the repository's .sha1 vouches for it, so it's always
 *       fetched, even for a cached jar. Never the .sha1 cached beside the jar: whoever can alter
 *       the jar can alter that too.
 * </ul>
 *
 * Nothing unverified is ever written to the local cache. A refusal is a failed Result whose
 * message says what to do; only file-system errors throw.
 */
public final class ArtifactStore {

    /** What vouches for a jar. Closed: there is no "unverified". */
    public sealed interface Expectation {
        Expectation PUBLISHED = new Published();

        static Expectation pinned(String sha256) {
            return new Pinned(sha256);
        }

        record Pinned(String sha256) implements Expectation {
        }

        record Published() implements Expectation {
        }
    }

    private final LocalRepository local;
    private final MavenRepository remote;

    public ArtifactStore(LocalRepository local, MavenRepository remote) {
        this.local = local;
        this.remote = remote;
    }

    /**
     * The POM's text, from the local cache if it's there, else from the repository (then cached).
     * POMs aren't checksum-verified yet; see AGENTS.md, "POMs are not verified".
     */
    public CompletableFuture<Result<String>> pom(Gav gav) {
        Path cached = local.path(gav, "pom");
        if (Files.exists(cached)) {
            return CompletableFuture.completedFuture(Result.ok(read(cached)));
        }
        return remote.fetch(gav, "pom").thenApply(fetched -> fetched.map(bytes -> {
            write(cached, bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }));
    }

    /** The jar, once {@code expected} vouches for it, or a failure saying why it doesn't. */
    public CompletableFuture<Result<Path>> jar(Gav gav, Expectation expected) {
        Path cached = local.path(gav, "jar");
        if (Files.exists(cached)) {
            byte[] bytes = readBytes(cached);
            return switch (expected) {
                case Expectation.Pinned pinned ->
                        CompletableFuture.completedFuture(matchesPin(gav, bytes, pinned, cached.toString(),
                                "The cached jar has changed since the lock was written; delete it so kwenza"
                                        + " re-downloads and re-verifies it.").map(ok -> cached));
                case Expectation.Published published -> publishedSha1(gav).thenApply(sha1 -> sha1.flatMap(expectedSha1 -> {
                    String actual = hex("SHA-1", bytes);
                    return actual.equalsIgnoreCase(expectedSha1)
                            ? Result.ok(cached)
                            : Result.err("Checksum mismatch for " + gav + ": " + cached + " has sha1 " + actual
                                    + " but the repository publishes " + expectedSha1 + ". The cached jar has been"
                                    + " altered; delete it so kwenza re-downloads and re-verifies it.");
                }));
            };
        }
        return remote.fetch(gav, "jar").thenCombine(publishedSha1(gav), (download, sha1) -> download.flatMap(bytes ->
                sha1.flatMap(expectedSha1 -> {
                    String actual = hex("SHA-1", bytes);
                    if (!actual.equalsIgnoreCase(expectedSha1)) {
                        return Result.<Path>err("Checksum mismatch for " + gav
                                + " (expected " + expectedSha1 + ", got " + actual + ")");
                    }
                    if (expected instanceof Expectation.Pinned pinned) {
                        Result<Void> pin = matchesPin(gav, bytes, pinned, "the repository's jar",
                                "Released artifacts never change, so check kwenza.lock wasn't edited by hand,"
                                        + " or delete it so the next build resolves again.");
                        if (!pin.isOk()) {
                            return pin.castErr();
                        }
                    }
                    write(cached, bytes);
                    return Result.ok(cached);
                })));
    }

    private static Result<Void> matchesPin(Gav gav, byte[] bytes, Expectation.Pinned pinned, String where,
                                           String whatToDo) {
        String actual = hex("SHA-256", bytes);
        if (actual.equalsIgnoreCase(pinned.sha256())) {
            return Result.ok();
        }
        return Result.err("Checksum mismatch for " + gav + ": kwenza.lock expects sha256 " + pinned.sha256()
                + " but " + where + " has " + actual + ". " + whatToDo);
    }

    /** The jar's published sha1 hex, failing closed if it's missing, unfetchable or blank. */
    private CompletableFuture<Result<String>> publishedSha1(Gav gav) {
        String url = remote.url(gav, "jar.sha1");
        return remote.fetch(gav, "jar.sha1").thenApply(fetched -> {
            if (fetched instanceof Result.Err<?> err) {
                return Result.err("Refusing to use " + gav + ": its checksum " + url + " couldn't be fetched ("
                        + err.failure().problems().get(0) + "), so the jar can't be verified");
            }
            String text = new String(fetched.get(), StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                return Result.err("Refusing to use " + gav + ": " + url + " is empty, so the jar can't be verified");
            }
            return Result.ok(text.split("\\s+")[0]);
        });
    }

    static String hex(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has " + algorithm, e);
        }
    }

    private static String read(Path path) {
        return new String(readBytes(path), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(Path path, byte[] bytes) {
        try {
            local.write(path, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
