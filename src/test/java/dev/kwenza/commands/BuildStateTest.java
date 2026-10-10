package dev.kwenza.commands;

import dev.kwenza.manifest.KwenzaLock;
import dev.kwenza.manifest.KwenzaManifest;
import dev.kwenza.resolver.MavenRepository;
import dev.kwenza.resolver.Resolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Real collaborators throughout except {@code Reporter} (a mock - the one real external-facing
 * boundary here, console output, silent by default with nothing to verify in these tests): a
 * real Resolver against a real MavenRepository, but pointed at a local repo pre-seeded with the
 * POM/jar it needs, plus a kwenza.lock pinning each seeded jar's sha256, so the real resolve path
 * runs with no network call. The lock is what makes that possible: a jar nothing pins gets checked
 * against the remote repository's .sha1 even on a cache hit, and these artifacts don't exist there.
 */
class BuildStateTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private Path localRepo;
    private final List<KwenzaLock.Entry> seeded = new ArrayList<>();

    private void setUp() throws IOException {
        projectRoot = tempDir.resolve("project");
        localRepo = tempDir.resolve("repo");
        Files.createDirectories(projectRoot);
        Files.createDirectories(localRepo);
    }

    private void seed(String group, String artifact, String version) throws Exception {
        MavenRepository repo = MavenRepository.central(localRepo);
        Path pomPath = repo.artifactPath(group, artifact, version, "pom");
        Files.createDirectories(pomPath.getParent());
        Files.writeString(pomPath, """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version));
        // A real, valid (if empty) jar - javac opens every classpath entry as a zip while
        // compiling, even ones the source under test doesn't actually reference.
        Path jarPath = repo.artifactPath(group, artifact, version, "jar");
        Files.createDirectories(jarPath.getParent());
        try (var jarOut = new java.util.jar.JarOutputStream(Files.newOutputStream(jarPath))) {
            // no entries needed - an empty jar is still a valid one
        }
        String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jarPath)));
        seeded.add(new KwenzaLock.Entry(group + ":" + artifact, version, sha256, List.of("direct")));
    }

    private void writeSource(Path root, String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private BuildState newState(String manifestText) throws IOException {
        KwenzaManifest manifest = KwenzaManifest.parse(manifestText, "project");
        if (!seeded.isEmpty()) {
            new KwenzaLock(seeded, null).save(projectRoot.resolve("kwenza.lock"), KwenzaLock.hashDependencies(manifest));
        }
        return new BuildState(manifest, projectRoot, projectRoot.resolve("kwenza.lock"),
                projectRoot.resolve("target"), localRepo, false, false, mock(Reporter.class));
    }

    @Test
    void ensureMainResolvedExcludesDevDependenciesFromItsClasspath() throws Exception {
        setUp();
        seed("com.example", "main-dep", "1.0");
        seed("com.example", "dev-dep", "1.0");
        BuildState state = newState("""
                [package]
                group = "com.example"
                version = "0.1.0"

                [dependencies]
                a = { module = "com.example:main-dep", version = "1.0" }

                [dev-dependencies]
                b = { module = "com.example:dev-dep", version = "1.0" }
                """);

        Resolver.Resolution mainOnly = state.ensureMainResolved();

        assertEquals(1, mainOnly.artifacts().size());
        assertEquals("com.example:main-dep", mainOnly.artifacts().get(0).moduleId());
    }

    @Test
    void ensureResolvedIncludesBothDependenciesAndDevDependencies() throws Exception {
        setUp();
        seed("com.example", "main-dep", "1.0");
        seed("com.example", "dev-dep", "1.0");
        BuildState state = newState("""
                [package]
                group = "com.example"
                version = "0.1.0"

                [dependencies]
                a = { module = "com.example:main-dep", version = "1.0" }

                [dev-dependencies]
                b = { module = "com.example:dev-dep", version = "1.0" }
                """);

        Resolver.Resolution merged = state.ensureResolved();

        assertEquals(2, merged.artifacts().size());
    }

    @Test
    void ensureCompiledProducesClassFilesFromSrcMainJava() throws Exception {
        setUp();
        writeSource(projectRoot, "src/main/java/com/example/Main.java", """
                package com.example;
                public final class Main {}
                """);
        BuildState state = newState("""
                [package]
                group = "com.example"
                version = "0.1.0"
                """);

        state.ensureCompiled();

        assertTrue(Files.exists(projectRoot.resolve("target/classes/com/example/Main.class")));
    }

    @Test
    void ensureCompiledCalledTwiceDoesNotRecompile() throws Exception {
        setUp();
        writeSource(projectRoot, "src/main/java/com/example/Main.java", """
                package com.example;
                public final class Main {}
                """);
        BuildState state = newState("""
                [package]
                group = "com.example"
                version = "0.1.0"
                """);

        state.ensureCompiled();
        Path classFile = projectRoot.resolve("target/classes/com/example/Main.class");
        assertTrue(Files.exists(classFile));
        Files.delete(classFile); // if the second call recompiles, this file reappears

        state.ensureCompiled();

        assertFalse(Files.exists(classFile), "ensureCompiled() recompiled even though nothing invalidated it");
    }

    @Test
    void invalidateMakesTheNextEnsureCompiledActuallyRecompile() throws Exception {
        setUp();
        writeSource(projectRoot, "src/main/java/com/example/Main.java", """
                package com.example;
                public final class Main {}
                """);
        BuildState state = newState("""
                [package]
                group = "com.example"
                version = "0.1.0"
                """);

        state.ensureCompiled();
        Path classFile = projectRoot.resolve("target/classes/com/example/Main.class");
        Files.delete(classFile);
        state.invalidate();

        state.ensureCompiled();

        assertTrue(Files.exists(classFile), "invalidate() should have made ensureCompiled() recompile");
    }

    @Test
    void ensureTestCompiledPutsBothMainAndTestDependenciesOnTheTestCompileClasspath() throws Exception {
        setUp();
        seed("com.example", "dev-dep", "1.0");
        writeSource(projectRoot, "src/main/java/com/example/Main.java", """
                package com.example;
                public final class Main {
                    public static String greet() { return "hi"; }
                }
                """);
        writeSource(projectRoot, "src/test/java/com/example/MainRef.java", """
                package com.example;
                public final class MainRef {
                    static { Main.greet(); } // references the compiled main output
                }
                """);
        BuildState state = newState("""
                [package]
                group = "com.example"
                version = "0.1.0"

                [dev-dependencies]
                b = { module = "com.example:dev-dep", version = "1.0" }
                """);

        state.ensureTestCompiled();

        assertTrue(Files.exists(projectRoot.resolve("target/test-classes/com/example/MainRef.class")));
    }
}
