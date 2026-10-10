package dev.kwenza.resolver;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The remote repository is the real external boundary here, so it's a real local HTTP server
 * (the JDK's own com.sun.net.httpserver - no extra dependency) serving Maven-layout paths, rather
 * than a mock: what's under test is exactly how MavenRepository reacts to what comes back over HTTP.
 */
class MavenRepositoryTest {

    private static final String JAR_PATH = "/com/example/leaf/1.0/leaf-1.0.jar";
    private static final byte[] JAR_BYTES = "pretend this is a jar".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path localRepo;

    private HttpServer server;
    private final Map<String, byte[]> served = new ConcurrentHashMap<>();
    private MavenRepository repo;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = served.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
        repo = new MavenRepository("http://127.0.0.1:" + server.getAddress().getPort(), localRepo);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private Path cachedJar() {
        return repo.artifactPath("com.example", "leaf", "1.0", "jar");
    }

    private ResolutionException fetchFails() {
        CompletionException e = assertThrows(CompletionException.class,
                () -> repo.fetchJarAsync("com.example", "leaf", "1.0").join());
        return assertInstanceOf(ResolutionException.class, e.getCause());
    }

    @Test
    void aJarMatchingItsPublishedSha1IsDownloadedIntoTheLocalCache() throws IOException {
        served.put(JAR_PATH, JAR_BYTES);
        served.put(JAR_PATH + ".sha1", (sha1(JAR_BYTES) + "  leaf-1.0.jar\n").getBytes(StandardCharsets.UTF_8));

        Path jar = repo.fetchJarAsync("com.example", "leaf", "1.0").join();

        assertArrayEquals(JAR_BYTES, Files.readAllBytes(jar));
    }

    @Test
    void aJarWithNoPublishedSha1IsRefusedRatherThanTrustedUnverified() {
        served.put(JAR_PATH, JAR_BYTES);

        ResolutionException e = fetchFails();

        assertTrue(e.getMessage().contains("com.example:leaf:1.0"), e.getMessage());
        assertFalse(Files.exists(cachedJar()), "an unverified jar must never land in the local cache");
    }

    @Test
    void aJarWithABlankPublishedSha1IsRefused() {
        served.put(JAR_PATH, JAR_BYTES);
        served.put(JAR_PATH + ".sha1", "\n".getBytes(StandardCharsets.UTF_8));

        fetchFails();

        assertFalse(Files.exists(cachedJar()));
    }

    @Test
    void aJarNotMatchingItsPublishedSha1IsRefused() {
        served.put(JAR_PATH, JAR_BYTES);
        served.put(JAR_PATH + ".sha1", sha1("something else".getBytes(StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));

        ResolutionException e = fetchFails();

        assertTrue(e.getMessage().contains("Checksum mismatch"), e.getMessage());
        assertFalse(Files.exists(cachedJar()));
    }

    @Test
    void aCachedJarMatchingTheRemoteSha1PassesVerification() throws IOException {
        served.put(JAR_PATH + ".sha1", sha1(JAR_BYTES).getBytes(StandardCharsets.UTF_8));
        writeCached("jar", JAR_BYTES);

        Path jar = repo.fetchJarVerifiedAsync("com.example", "leaf", "1.0").join();

        assertArrayEquals(JAR_BYTES, Files.readAllBytes(jar));
    }

    @Test
    void aCachedJarIsCheckedAgainstTheRemoteSha1NotTheOneCachedBesideIt() throws IOException {
        byte[] tampered = "tampered".getBytes(StandardCharsets.UTF_8);
        served.put(JAR_PATH + ".sha1", sha1(JAR_BYTES).getBytes(StandardCharsets.UTF_8));
        writeCached("jar", tampered);
        writeCached("jar.sha1", sha1(tampered).getBytes(StandardCharsets.UTF_8));

        CompletionException e = assertThrows(CompletionException.class,
                () -> repo.fetchJarVerifiedAsync("com.example", "leaf", "1.0").join());

        ResolutionException cause = assertInstanceOf(ResolutionException.class, e.getCause());
        assertTrue(cause.getMessage().contains(cachedJar().toString()), cause.getMessage());
    }

    @Test
    void aCachedJarWithNoRemoteSha1IsRefused() throws IOException {
        writeCached("jar", JAR_BYTES);

        CompletionException e = assertThrows(CompletionException.class,
                () -> repo.fetchJarVerifiedAsync("com.example", "leaf", "1.0").join());

        assertInstanceOf(ResolutionException.class, e.getCause());
    }

    private void writeCached(String extension, byte[] bytes) throws IOException {
        Path path = repo.artifactPath("com.example", "leaf", "1.0", extension);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
