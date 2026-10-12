package dev.kwenza.commands;

import dev.kwenza.context.Reporter;
import dev.kwenza.resolver.MavenRepository;
import dev.kwenza.resolver.Resolver;
import dev.kwenza.testsupport.Fixtures.Contexts;
import dev.kwenza.testsupport.Fixtures.SeededRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.kwenza.testsupport.Fixtures.contexts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Real resolution against a local repository pre-seeded with every POM and jar, plus a kwenza.lock
 * pinning each seeded jar (see {@link SeededRepository}), so nothing needs the network.
 */
class ResolveCommandTest {

    private static final String BOTH_SCOPES = """
            [package]
            name = "my-app"
            group = "com.example"
            version = "0.1.0"

            [dependencies]
            a = { module = "com.example:main-dep", version = "1.0" }

            [dev-dependencies]
            b = { module = "com.example:dev-dep", version = "1.0" }
            """;

    @TempDir
    Path tempDir;

    private Contexts seededProject(Reporter reporter, MavenRepository remote) throws Exception {
        SeededRepository repo = new SeededRepository(tempDir.resolve("repo"));
        repo.seed("com.example", "main-dep", "1.0");
        repo.seed("com.example", "dev-dep", "1.0");
        Contexts contexts = contexts(tempDir.resolve("project"), repo.path(), BOTH_SCOPES, reporter, remote);
        Files.createDirectories(contexts.project().root());
        repo.lock(contexts.project());
        return contexts;
    }

    @Test
    void writesKwenzaLockWithoutCompilingAnything() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Contexts state = contexts(projectRoot, tempDir.resolve("repo"), """
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                """);

        state.resolve().run().get();

        assertTrue(Files.exists(projectRoot.resolve("kwenza.lock")));
        assertTrue(Files.notExists(projectRoot.resolve("target")));
    }

    @Test
    void resolvedIncludesBothDependenciesAndDevDependencies() throws Exception {
        Contexts state = seededProject(mock(Reporter.class), MavenRepository.central());

        assertEquals(2, state.resolve().resolved().get().artifacts().size());
    }

    @Test
    void mainResolvedExcludesDevDependencies() throws Exception {
        Contexts state = seededProject(mock(Reporter.class), MavenRepository.central());

        Resolver.Resolution mainOnly = state.resolve().mainResolved().get();

        assertEquals(1, mainOnly.artifacts().size());
        assertEquals("com.example:main-dep", mainOnly.artifacts().get(0).moduleId());
    }

    @Test
    void aFreshLockWithEveryJarCachedNeverTouchesTheNetworkOrRewritesTheLock() throws Exception {
        MavenRepository remote = mock(MavenRepository.class);
        Reporter reporter = mock(Reporter.class);
        Contexts state = seededProject(reporter, remote);

        state.resolve().resolved().get();

        verifyNoInteractions(remote);
        verify(reporter).info("resolve: using locked dependencies (kwenza.lock)");
        verify(reporter, never()).info(startsWith("resolve: fetching"));
    }

    @Test
    void chainedCommandsShareOneResolution() throws Exception {
        Reporter reporter = mock(Reporter.class);
        Contexts state = seededProject(reporter, MavenRepository.central());

        state.resolve().resolved().get();
        state.resolve().resolved().get(); // a second command in the same chain, a fresh ResolveCommand

        verify(reporter, times(1)).info("resolve: using locked dependencies (kwenza.lock)");
    }
}
