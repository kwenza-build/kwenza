package dev.kwenza.resolver;

import com.sun.net.httpserver.HttpServer;
import dev.kwenza.context.Result;
import dev.kwenza.resolver.ArtifactStore.Expectation;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one trust rule for jars, end to end: a real local HTTP server (the JDK's own
 * com.sun.net.httpserver - no extra dependency) serving Maven-layout paths, a real
 * MavenRepository talking to it, and a real local repository in a temp directory. What's under
 * test is exactly what ArtifactStore accepts and refuses, and that nothing unverified ever lands
 * in the local cache.
 */
class ArtifactStoreTest {

    private static final Gav LEAF = new Gav("com.example", "leaf", "1.0");
    private static final String JAR_PATH = "/com/example/leaf/1.0/leaf-1.0.jar";
    private static final byte[] JAR_BYTES = "pretend this is a jar".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path localRepoDir;

    private HttpServer server;
    private final Map<String, byte[]> served = new ConcurrentHashMap<>();
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private LocalRepository local;
    private ArtifactStore store;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requested.add(exchange.getRequestURI().getPath());
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
        local = new LocalRepository(localRepoDir);
        store = new ArtifactStore(local,
                new MavenRepository("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private Path cachedJar() {
        return local.path(LEAF, "jar");
    }

    private Result<Path> jar(Expectation expectation) {
        return store.jar(LEAF, expectation).join();
    }

    private void serveJarWithItsSha1() {
        served.put(JAR_PATH, JAR_BYTES);
        served.put(JAR_PATH + ".sha1", (sha1(JAR_BYTES) + "  leaf-1.0.jar\n").getBytes(StandardCharsets.UTF_8));
    }

    // Published: the repository's .sha1 is the only thing that vouches for the jar.

    @Test
    void aDownloadedJarMatchingItsPublishedSha1LandsInTheLocalCache() throws IOException {
        serveJarWithItsSha1();

        Path jar = jar(Expectation.PUBLISHED).get();

        assertArrayEquals(JAR_BYTES, Files.readAllBytes(jar));
    }

    @Test
    void aJarWithNoPublishedSha1IsRefusedRatherThanTrustedUnverified() {
        served.put(JAR_PATH, JAR_BYTES);

        String problem = jar(Expectation.PUBLISHED).failure().problems().get(0);

        assertTrue(problem.contains("com.example:leaf:1.0"), problem);
        assertFalse(Files.exists(cachedJar()), "an unverified jar must never land in the local cache");
    }

    @Test
    void aJarWithABlankPublishedSha1IsRefused() {
        served.put(JAR_PATH, JAR_BYTES);
        served.put(JAR_PATH + ".sha1", "\n".getBytes(StandardCharsets.UTF_8));

        assertFalse(jar(Expectation.PUBLISHED).isOk());
        assertFalse(Files.exists(cachedJar()));
    }

    @Test
    void aJarNotMatchingItsPublishedSha1IsRefused() {
        served.put(JAR_PATH, JAR_BYTES);
        served.put(JAR_PATH + ".sha1", sha1("something else".getBytes(StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));

        String problem = jar(Expectation.PUBLISHED).failure().problems().get(0);

        assertTrue(problem.contains("Checksum mismatch"), problem);
        assertFalse(Files.exists(cachedJar()));
    }

    @Test
    void aCachedJarMatchingTheRemoteSha1PassesVerification() throws IOException {
        served.put(JAR_PATH + ".sha1", sha1(JAR_BYTES).getBytes(StandardCharsets.UTF_8));
        writeCached("jar", JAR_BYTES);

        Path jar = jar(Expectation.PUBLISHED).get();

        assertArrayEquals(JAR_BYTES, Files.readAllBytes(jar));
    }

    @Test
    void aCachedJarIsCheckedAgainstTheRemoteSha1NotTheOneCachedBesideIt() throws IOException {
        byte[] tampered = "tampered".getBytes(StandardCharsets.UTF_8);
        served.put(JAR_PATH + ".sha1", sha1(JAR_BYTES).getBytes(StandardCharsets.UTF_8));
        writeCached("jar", tampered);
        writeCached("jar.sha1", sha1(tampered).getBytes(StandardCharsets.UTF_8));

        String problem = jar(Expectation.PUBLISHED).failure().problems().get(0);

        assertTrue(problem.contains(cachedJar().toString()), problem);
    }

    @Test
    void aCachedJarWithNoRemoteSha1IsRefused() throws IOException {
        writeCached("jar", JAR_BYTES);

        assertFalse(jar(Expectation.PUBLISHED).isOk());
    }

    // Pinned: kwenza.lock's sha256 vouches for the jar.

    @Test
    void aCachedJarMatchingItsPinIsUsedWithoutAnyNetworkRequest() throws IOException {
        writeCached("jar", JAR_BYTES);

        Path jar = jar(Expectation.pinned(sha256(JAR_BYTES))).get();

        assertEquals(cachedJar(), jar);
        assertEquals(List.of(), requested);
    }

    @Test
    void aCachedJarThatNoLongerMatchesItsPinIsRefusedNamingThePinAndThePath() throws IOException {
        String pin = sha256(JAR_BYTES);
        writeCached("jar", "tampered with after the lock was written".getBytes(StandardCharsets.UTF_8));

        String problem = jar(Expectation.pinned(pin)).failure().problems().get(0);

        assertTrue(problem.contains("com.example:leaf:1.0"), problem);
        assertTrue(problem.contains(pin), problem);
        assertTrue(problem.contains(cachedJar().toString()), problem);
    }

    @Test
    void aPinnedJarMissingFromTheCacheIsDownloadedVerifiedAndCached() {
        serveJarWithItsSha1();

        assertTrue(jar(Expectation.pinned(sha256(JAR_BYTES))).isOk());
        assertTrue(Files.exists(cachedJar()));
        assertTrue(requested.contains(JAR_PATH + ".sha1"), "a download is still checked against the published sha1");
    }

    @Test
    void aDownloadedJarThatDoesNotMatchItsPinIsRefusedAndNotCached() {
        serveJarWithItsSha1();

        assertFalse(jar(Expectation.pinned("0".repeat(64))).isOk());
        assertFalse(Files.exists(cachedJar()));
    }

    // POMs follow the same rule as jars.

    private static final String POM_PATH = "/com/example/leaf/1.0/leaf-1.0.pom";
    private static final byte[] POM_BYTES = "<project/>".getBytes(StandardCharsets.UTF_8);

    private void servePomWithItsSha1() {
        served.put(POM_PATH, POM_BYTES);
        served.put(POM_PATH + ".sha1", sha1(POM_BYTES).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aPomMatchingItsPublishedSha1IsCachedAndReportsItsSha256() {
        servePomWithItsSha1();

        ArtifactStore.VerifiedPom pom = store.pom(LEAF, Expectation.PUBLISHED).join().get();

        assertEquals("<project/>", pom.text());
        assertEquals(sha256(POM_BYTES), pom.sha256());
        assertTrue(Files.exists(local.path(LEAF, "pom")));
    }

    @Test
    void aPomWithNoPublishedSha1IsRefusedAndNotCached() {
        served.put(POM_PATH, POM_BYTES);

        String problem = store.pom(LEAF, Expectation.PUBLISHED).join().failure().problems().get(0);

        assertTrue(problem.contains("com.example:leaf:1.0"), problem);
        assertFalse(Files.exists(local.path(LEAF, "pom")));
    }

    @Test
    void aPomNotMatchingItsPublishedSha1IsRefusedAndNotCached() {
        served.put(POM_PATH, POM_BYTES);
        served.put(POM_PATH + ".sha1", sha1("other".getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));

        String problem = store.pom(LEAF, Expectation.PUBLISHED).join().failure().problems().get(0);

        assertTrue(problem.contains("Checksum mismatch"), problem);
        assertFalse(Files.exists(local.path(LEAF, "pom")));
    }

    @Test
    void aCachedPomIsCheckedAgainstTheRemoteSha1() throws IOException {
        served.put(POM_PATH + ".sha1", sha1(POM_BYTES).getBytes(StandardCharsets.UTF_8));
        writeCached("pom", "<project>tampered</project>".getBytes(StandardCharsets.UTF_8));

        String problem = store.pom(LEAF, Expectation.PUBLISHED).join().failure().problems().get(0);

        assertTrue(problem.contains(local.path(LEAF, "pom").toString()), problem);
    }

    @Test
    void aCachedPomMatchingItsPinIsUsedWithoutAnyNetworkRequest() throws IOException {
        writeCached("pom", POM_BYTES);

        assertEquals("<project/>", store.pom(LEAF, Expectation.pinned(sha256(POM_BYTES))).join().get().text());
        assertEquals(List.of(), requested);
    }

    @Test
    void aCachedPomThatNoLongerMatchesItsPinIsRefused() throws IOException {
        String pin = sha256(POM_BYTES);
        writeCached("pom", "<project>tampered</project>".getBytes(StandardCharsets.UTF_8));

        String problem = store.pom(LEAF, Expectation.pinned(pin)).join().failure().problems().get(0);

        assertTrue(problem.contains(pin), problem);
        assertTrue(problem.contains("com.example:leaf:1.0"), problem);
    }

    @Test
    void aMissingPomIsAFailureNamingTheUrl() {
        String problem = store.pom(LEAF, Expectation.PUBLISHED).join().failure().problems().get(0);

        assertTrue(problem.contains("leaf-1.0.pom"), problem);
        assertTrue(problem.contains("404"), problem);
    }

    private void writeCached(String extension, byte[] bytes) throws IOException {
        Path path = local.path(LEAF, extension);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private static String sha1(byte[] bytes) {
        return hex("SHA-1", bytes);
    }

    private static String sha256(byte[] bytes) {
        return hex("SHA-256", bytes);
    }

    private static String hex(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
