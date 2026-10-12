package dev.kwenza.resolver;

import dev.kwenza.manifest.Dependency;
import dev.kwenza.manifest.KwenzaLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MavenRepository is the one real external-API boundary here (network) - it's mocked so
 * mediation logic is exercised without any real network call. Everything it returns still has
 * to point at a real file on disk, since Resolver genuinely hashes the jar bytes it's handed.
 */
class ResolverTest {

    @TempDir
    Path tempDir;

    private MavenRepository repo;
    private Resolver resolver;

    private void mockPom(String group, String artifact, String version, String xml) {
        when(repo.fetchTextAsync(eq(group), eq(artifact), eq(version), eq("pom")))
                .thenReturn(CompletableFuture.completedFuture(xml));
    }

    private Path mockJar(String group, String artifact, String version) throws IOException {
        Path jar = tempDir.resolve(artifact + "-" + version + ".jar");
        Files.writeString(jar, group + ":" + artifact + ":" + version); // fake but real, hashable content
        when(repo.fetchJarAsync(eq(group), eq(artifact), eq(version)))
                .thenReturn(CompletableFuture.completedFuture(jar));
        when(repo.fetchJarVerifiedAsync(eq(group), eq(artifact), eq(version)))
                .thenReturn(CompletableFuture.completedFuture(jar));
        return jar;
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

    private void setUp() {
        repo = mock(MavenRepository.class);
        resolver = new Resolver(repo);
    }

    @Test
    void aDirectDependencyWithNoTransitivesResolvesToItself() throws IOException {
        setUp();
        mockPom("com.example", "leaf", "1.0", pom("com.example", "leaf", "1.0", ""));
        mockJar("com.example", "leaf", "1.0");

        Resolver.Resolution resolution = resolver.resolve(
                List.of(new Dependency("leaf", "com.example", "leaf", "1.0")));

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

        Resolver.Resolution resolution = resolver.resolve(
                List.of(new Dependency("app", "com.example", "app", "1.0")));

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

        Resolver.Resolution resolution = resolver.resolve(
                List.of(new Dependency("app", "com.example", "app", "1.0")));

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

        Resolver.Resolution resolution = resolver.resolve(
                List.of(new Dependency("app", "com.example", "app", "1.0")));

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

        Resolver.Resolution resolution = resolver.resolve(
                List.of(new Dependency("app", "com.example", "app", "1.0")));

        assertEquals("3.0", byModule(resolution).get("com.example:lib").version());
    }

    @Test
    void ensureFromLockDownloadsEachEntryWithoutFetchingAnyPom() throws IOException {
        setUp();
        Path jar = mockJar("com.example", "leaf", "1.0");
        KwenzaLock lock = new KwenzaLock(
                List.of(new KwenzaLock.Entry("com.example:leaf", "1.0", sha256(jar), List.of("direct"))),
                "some-hash");

        Resolver.Resolution resolution = resolver.ensureFromLock(lock);

        assertEquals(1, resolution.artifacts().size());
        assertEquals("1.0", resolution.artifacts().get(0).version());
        assertTrue(resolution.artifacts().get(0).jarPath().toString().endsWith("leaf-1.0.jar"));
    }

    @Test
    void ensureFromLockRejectsAJarWhoseContentNoLongerMatchesTheLock() throws IOException {
        setUp();
        Path jar = mockJar("com.example", "leaf", "1.0");
        String lockedSha256 = sha256(jar);
        Files.writeString(jar, "tampered with after the lock was written");
        KwenzaLock lock = new KwenzaLock(
                List.of(new KwenzaLock.Entry("com.example:leaf", "1.0", lockedSha256, List.of("direct"))),
                "some-hash");

        ResolutionException e = assertThrows(ResolutionException.class, () -> resolver.ensureFromLock(lock));

        assertTrue(e.getMessage().contains("com.example:leaf:1.0"), e.getMessage());
        assertTrue(e.getMessage().contains(lockedSha256), e.getMessage());
        assertTrue(e.getMessage().contains(jar.toString()), e.getMessage());
    }

    @Test
    void ensureFromLockRejectsALockEntryWithNoRecordedChecksum() throws IOException {
        setUp();
        mockJar("com.example", "leaf", "1.0");
        KwenzaLock lock = new KwenzaLock(
                List.of(new KwenzaLock.Entry("com.example:leaf", "1.0", null, List.of("direct"))),
                "some-hash");

        ResolutionException e = assertThrows(ResolutionException.class, () -> resolver.ensureFromLock(lock));

        assertTrue(e.getMessage().contains("com.example:leaf:1.0"), e.getMessage());
        assertTrue(e.getMessage().contains("Delete kwenza.lock"), e.getMessage());
    }

    @Test
    void aFreshResolveChecksAnUnchangedArtifactAgainstThePreviousLock() throws IOException {
        setUp();
        mockPom("com.example", "leaf", "1.0", pom("com.example", "leaf", "1.0", ""));
        Path jar = mockJar("com.example", "leaf", "1.0");
        KwenzaLock previous = new KwenzaLock(
                List.of(new KwenzaLock.Entry("com.example:leaf", "1.0", sha256(jar), List.of("direct"))), "old-hash");
        Files.writeString(jar, "tampered with in the local cache");

        ResolutionException e = assertThrows(ResolutionException.class, () -> resolver.resolve(
                List.of(new Dependency("leaf", "com.example", "leaf", "1.0")), previous));

        assertTrue(e.getMessage().contains("com.example:leaf:1.0"), e.getMessage());
    }

    @Test
    void aFreshResolveTrustsThePreviousLocksPinInsteadOfGoingBackToTheRepository() throws IOException {
        setUp();
        mockPom("com.example", "leaf", "1.0", pom("com.example", "leaf", "1.0", ""));
        Path jar = mockJar("com.example", "leaf", "1.0");
        KwenzaLock previous = new KwenzaLock(
                List.of(new KwenzaLock.Entry("com.example:leaf", "1.0", sha256(jar), List.of("direct"))), "old-hash");

        resolver.resolve(List.of(new Dependency("leaf", "com.example", "leaf", "1.0")), previous);

        verify(repo, never()).fetchJarVerifiedAsync(any(), any(), any());
    }

    @Test
    void aFreshResolveVerifiesArtifactsThePreviousLockDoesNotPinAgainstTheRepository() throws IOException {
        setUp();
        mockPom("com.example", "leaf", "1.0", pom("com.example", "leaf", "1.0", ""));
        mockJar("com.example", "leaf", "1.0");
        KwenzaLock previous = new KwenzaLock(
                List.of(new KwenzaLock.Entry("com.example:leaf", "0.9", "pin-for-an-older-version", List.of("direct"))),
                "old-hash");

        resolver.resolve(List.of(new Dependency("leaf", "com.example", "leaf", "1.0")), previous);

        verify(repo).fetchJarVerifiedAsync("com.example", "leaf", "1.0");
        verify(repo, never()).fetchJarAsync(any(), any(), any());
    }

    @Test
    void aJarThatFailsVerificationReportsWhyNotJustThatItFailed() throws IOException {
        setUp();
        mockPom("com.example", "leaf", "1.0", pom("com.example", "leaf", "1.0", ""));
        when(repo.fetchJarVerifiedAsync(eq("com.example"), eq("leaf"), eq("1.0")))
                .thenReturn(CompletableFuture.failedFuture(new ResolutionException("Checksum mismatch, delete it")));

        ResolutionException e = assertThrows(ResolutionException.class, () -> resolver.resolve(
                List.of(new Dependency("leaf", "com.example", "leaf", "1.0"))));

        assertTrue(e.getMessage().contains("Checksum mismatch, delete it"), e.getMessage());
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
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
