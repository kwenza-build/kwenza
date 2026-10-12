package dev.kwenza.resolver;

import dev.kwenza.context.Result;
import dev.kwenza.manifest.Dependency;
import dev.kwenza.manifest.KwenzaLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * MavenRepository is the one real external boundary here (the network), so it's mocked: anything
 * not explicitly served answers like a 404. Everything else is real, including the local
 * repository (a temp directory) and the one trust rule in ArtifactStore, so these tests see
 * exactly which jars get checked how.
 */
class ResolverTest {

    @TempDir
    Path tempDir;

    private MavenRepository repo;
    private LocalRepository local;
    private Resolver resolver;

    private void setUp() {
        repo = mock(MavenRepository.class, withSettings().defaultAnswer(invocation ->
                invocation.getMethod().getName().equals("fetch")
                        ? CompletableFuture.completedFuture(Result.err("Failed to fetch (HTTP 404)"))
                        : invocation.callRealMethod()));
        local = new LocalRepository(tempDir.resolve("repo"));
        resolver = new Resolver(new ArtifactStore(local, repo));
    }

    private static Gav gav(String group, String artifact, String version) {
        return new Gav(group, artifact, version);
    }

    private void serve(Gav gav, String extension, byte[] bytes) {
        when(repo.fetch(eq(gav), eq(extension))).thenReturn(CompletableFuture.completedFuture(Result.ok(bytes)));
    }

    private void mockPom(String group, String artifact, String version, String xml) {
        serve(gav(group, artifact, version), "pom", xml.getBytes(StandardCharsets.UTF_8));
    }

    /** Serves a jar and its matching .sha1, as Central would. */
    private byte[] mockJar(String group, String artifact, String version) {
        byte[] bytes = (group + ":" + artifact + ":" + version).getBytes(StandardCharsets.UTF_8);
        serve(gav(group, artifact, version), "jar", bytes);
        serve(gav(group, artifact, version), "jar.sha1", hex("SHA-1", bytes).getBytes(StandardCharsets.UTF_8));
        return bytes;
    }

    /** Puts a jar straight into the local cache, as an earlier build would have. */
    private Path cacheJar(String group, String artifact, String version, byte[] bytes) throws IOException {
        Path path = local.path(gav(group, artifact, version), "jar");
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return path;
    }

    private Resolver.Resolution resolve(KwenzaLock previous, Dependency... roots) {
        return resolver.resolve(List.of(roots), previous, "hash").get();
    }

    private Resolver.Resolution resolve(Dependency... roots) {
        return resolve(KwenzaLock.empty(), roots);
    }

    private static Dependency dep(String artifact, String version) {
        return new Dependency(artifact, "com.example", artifact, version);
    }

    private static String pom(String group, String artifact, String version, String dependenciesXml) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>
                    %s
                  </dependencies>
                </project>
                """.formatted(group, artifact, version, dependenciesXml);
    }

    private static String dependencyXml(String group, String artifact, String version, String scope) {
        return """
                <dependency>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <scope>%s</scope>
                </dependency>
                """.formatted(group, artifact, version, scope);
    }

    @Test
    void aDirectDependencyWithNoTransitivesResolvesToItself() throws IOException {
        setUp();
        mockPom("com.example", "leaf", "1.0", pom("com.example", "leaf", "1.0", ""));
        mockJar("com.example", "leaf", "1.0");

        Resolver.Resolution resolution = resolve(dep("leaf", "1.0"));

        assertEquals(1, resolution.artifacts().size());
        Resolver.ResolvedArtifact artifact = resolution.artifacts().get(0);
        assertEquals("com.example:leaf", artifact.moduleId());
        assertEquals("1.0", artifact.version());
        assertEquals(Set.of("direct"), artifact.requestedBy());
    }

    @Test
    void transitiveDependenciesAreFollowedAndTaggedWithTheirRequester() throws IOException {
        setUp();
        mockPom("com.example", "app", "1.0",
                pom("com.example", "app", "1.0", dependencyXml("com.example", "lib", "2.0", "compile")));
        mockJar("com.example", "app", "1.0");
        mockPom("com.example", "lib", "2.0", pom("com.example", "lib", "2.0", ""));
        mockJar("com.example", "lib", "2.0");

        Resolver.Resolution resolution = resolve(dep("app", "1.0"));

        Map<String, Resolver.ResolvedArtifact> byModule = byModule(resolution);
        assertEquals(2, resolution.artifacts().size());
        assertEquals(Set.of("com.example:app"), byModule.get("com.example:lib").requestedBy());
    }

    @Test
    void theNearerVersionWinsWhenTheSameModuleIsReachableTwoWays() throws IOException {
        setUp();
        // app depends directly on shared:2.0 (depth 0) *and* on helper (depth 0), which itself
        // depends on shared:1.0 (depth 1). The direct, shallower request should win.
        String appDeps = dependencyXml("com.example", "shared", "2.0", "compile")
                + dependencyXml("com.example", "helper", "1.0", "compile");
        mockPom("com.example", "app", "1.0", pom("com.example", "app", "1.0", appDeps));
        mockJar("com.example", "app", "1.0");
        mockPom("com.example", "helper", "1.0",
                pom("com.example", "helper", "1.0", dependencyXml("com.example", "shared", "1.0", "compile")));
        mockJar("com.example", "helper", "1.0");
        mockPom("com.example", "shared", "2.0", pom("com.example", "shared", "2.0", ""));
        mockJar("com.example", "shared", "2.0");

        Resolver.Resolution resolution = resolve(dep("app", "1.0"));

        assertEquals("2.0", byModule(resolution).get("com.example:shared").version());
    }

    @Test
    void optionalAndNonTransitiveScopeDependenciesAreNotFollowed() throws IOException {
        setUp();
        String appDeps = dependencyXml("com.example", "test-only", "1.0", "test")
                + """
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>optional-one</artifactId>
                  <version>1.0</version>
                  <optional>true</optional>
                </dependency>
                """;
        mockPom("com.example", "app", "1.0", pom("com.example", "app", "1.0", appDeps));
        mockJar("com.example", "app", "1.0");

        Resolver.Resolution resolution = resolve(dep("app", "1.0"));

        assertEquals(1, resolution.artifacts().size());
        assertEquals("com.example:app", resolution.artifacts().get(0).moduleId());
    }

    @Test
    void dependencyManagementSuppliesTheVersionForAnUnversionedDependency() throws IOException {
        setUp();
        String managed = """
                <dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>3.0</version>
                    </dependency>
                  </dependencies>
                </dependencyManagement>
                """;
        String unversionedDep = """
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                </dependency>
                """;
        mockPom("com.example", "app", "1.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  %s
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(managed, unversionedDep));
        mockJar("com.example", "app", "1.0");
        mockPom("com.example", "lib", "3.0", pom("com.example", "lib", "3.0", ""));
        mockJar("com.example", "lib", "3.0");

        Resolver.Resolution resolution = resolve(dep("app", "1.0"));

        assertEquals("3.0", byModule(resolution).get("com.example:lib").version());
    }

    @Test
    void theLockItReturnsIsCompleteWithTheManifestHash() {
        setUp();
        mockPom("com.example", "leaf", "1.0", pom("com.example", "leaf", "1.0", ""));
        byte[] jar = mockJar("com.example", "leaf", "1.0");

        Resolver.Resolution resolution = resolve(dep("leaf", "1.0"));

        assertEquals("hash", resolution.lock().manifestHash);
        assertEquals(hex("SHA-256", jar), resolution.lock().entries.get(0).sha256);
    }

    @Test
    void fromALockEveryCachedJarIsCheckedLocallyWithNoNetworkAtAll() throws IOException {
        setUp();
        byte[] bytes = "leaf".getBytes(StandardCharsets.UTF_8);
        cacheJar("com.example", "leaf", "1.0", bytes);
        KwenzaLock lock = new KwenzaLock(
                List.of(new KwenzaLock.Entry("com.example:leaf", "1.0", hex("SHA-256", bytes), List.of("direct"))),
                "hash");

        Resolver.Resolution resolution = resolver.fromLock(lock).get();

        assertEquals("1.0", resolution.artifacts().get(0).version());
        assertTrue(resolution.fromLock());
        verifyNoInteractions(repo);
    }

    @Test
    void fromALockEveryJarThatNoLongerMatchesIsReportedInOneGo() throws IOException {
        setUp();
        Path a = cacheJar("com.example", "a", "1.0", "tampered a".getBytes(StandardCharsets.UTF_8));
        Path b = cacheJar("com.example", "b", "1.0", "tampered b".getBytes(StandardCharsets.UTF_8));
        KwenzaLock lock = new KwenzaLock(List.of(
                new KwenzaLock.Entry("com.example:a", "1.0", "0".repeat(64), List.of("direct")),
                new KwenzaLock.Entry("com.example:b", "1.0", "1".repeat(64), List.of("direct"))), "hash");

        List<String> problems = resolver.fromLock(lock).failure().problems();

        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains(a.toString()), problems.get(0));
        assertTrue(problems.get(1).contains(b.toString()), problems.get(1));
    }

    @Test
    void aLockEntryWithNoRecordedChecksumIsAFailureSayingHowToRecover() {
        setUp();
        KwenzaLock lock = new KwenzaLock(
                List.of(new KwenzaLock.Entry("com.example:leaf", "1.0", null, List.of("direct"))), "hash");

        String problem = resolver.fromLock(lock).failure().problems().get(0);

        assertTrue(problem.contains("com.example:leaf:1.0"), problem);
        assertTrue(problem.contains("Delete kwenza.lock"), problem);
    }

    @Test
    void aFreshResolveChecksACachedUnchangedArtifactAgainstThePreviousLockNotTheRepository() throws IOException {
        setUp();
        mockPom("com.example", "leaf", "1.0", pom("com.example", "leaf", "1.0", ""));
        byte[] bytes = "leaf".getBytes(StandardCharsets.UTF_8);
        cacheJar("com.example", "leaf", "1.0", bytes);
        KwenzaLock previous = new KwenzaLock(
                List.of(new KwenzaLock.Entry("com.example:leaf", "1.0", hex("SHA-256", bytes), List.of("direct"))), "old");

        resolve(previous, dep("leaf", "1.0"));

        verify(repo, never()).fetch(any(), eq("jar.sha1"));
        verify(repo, never()).fetch(any(), eq("jar"));
    }

    @Test
    void aFreshResolveChecksACachedArtifactThePreviousLockDoesNotPinAgainstTheRepository() throws IOException {
        setUp();
        mockPom("com.example", "leaf", "1.0", pom("com.example", "leaf", "1.0", ""));
        byte[] bytes = mockJar("com.example", "leaf", "1.0");
        cacheJar("com.example", "leaf", "1.0", bytes);
        KwenzaLock previous = new KwenzaLock(
                List.of(new KwenzaLock.Entry("com.example:leaf", "0.9", "pin-for-an-older-version", List.of("direct"))),
                "old");

        resolve(previous, dep("leaf", "1.0"));

        verify(repo).fetch(gav("com.example", "leaf", "1.0"), "jar.sha1");
    }

    @Test
    void aFreshResolveRefusesACachedJarThatNoLongerMatchesThePreviousLock() throws IOException {
        setUp();
        mockPom("com.example", "leaf", "1.0", pom("com.example", "leaf", "1.0", ""));
        byte[] bytes = "leaf".getBytes(StandardCharsets.UTF_8);
        KwenzaLock previous = new KwenzaLock(
                List.of(new KwenzaLock.Entry("com.example:leaf", "1.0", hex("SHA-256", bytes), List.of("direct"))), "old");
        cacheJar("com.example", "leaf", "1.0", "tampered with in the local cache".getBytes(StandardCharsets.UTF_8));

        String problem = resolver.resolve(List.of(dep("leaf", "1.0")), previous, "hash").failure().problems().get(0);

        assertTrue(problem.contains("com.example:leaf:1.0"), problem);
    }

    @Test
    void aPomThatCannotBeFetchedIsAFailureNamingTheModule() {
        setUp();

        String problem = resolver.resolve(List.of(dep("missing", "1.0")), KwenzaLock.empty(), "hash")
                .failure().problems().get(0);

        assertTrue(problem.contains("com.example:missing:1.0"), problem);
        assertTrue(problem.contains("404"), problem);
    }

    private static String hex(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Resolver.ResolvedArtifact> byModule(Resolver.Resolution resolution) {
        Map<String, Resolver.ResolvedArtifact> map = new java.util.LinkedHashMap<>();
        for (Resolver.ResolvedArtifact a : resolution.artifacts()) {
            map.put(a.moduleId(), a);
        }
        return map;
    }
}
