package dev.kwenza.resolver;

import dev.kwenza.context.Reporter;
import dev.kwenza.testsupport.Fixtures;
import dev.kwenza.testsupport.Fixtures.Contexts;
import dev.kwenza.testsupport.Fixtures.SeededRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A real Resolver against a real MavenRepository, pointed at a pre-seeded local repo with a lock
 * pinning every seeded jar (see {@link SeededRepository}), so nothing touches the network.
 */
class ResolveContextTest {

    private static final String BOTH_SCOPES = """
            [package]
            group = "com.example"
            version = "0.1.0"

            [dependencies]
            a = { module = "com.example:main-dep", version = "1.0" }

            [dev-dependencies]
            b = { module = "com.example:dev-dep", version = "1.0" }
            """;

    @TempDir
    Path tempDir;

    private Contexts seededProject(Reporter reporter) throws Exception {
        SeededRepository repo = new SeededRepository(tempDir.resolve("repo"));
        repo.seed("com.example", "main-dep", "1.0");
        repo.seed("com.example", "dev-dep", "1.0");
        Contexts contexts = Fixtures.contexts(tempDir.resolve("project"), repo.path(), BOTH_SCOPES, reporter);
        java.nio.file.Files.createDirectories(contexts.project().root());
        repo.lock(contexts.project());
        return contexts;
    }

    @Test
    void ensureMainResolvedExcludesDevDependenciesFromItsClasspath() throws Exception {
        ResolveContext resolve = seededProject(mock(Reporter.class)).resolve();

        Resolver.Resolution mainOnly = resolve.ensureMainResolved();

        assertEquals(1, mainOnly.artifacts().size());
        assertEquals("com.example:main-dep", mainOnly.artifacts().get(0).moduleId());
    }

    @Test
    void ensureResolvedIncludesBothDependenciesAndDevDependencies() throws Exception {
        ResolveContext resolve = seededProject(mock(Reporter.class)).resolve();

        Resolver.Resolution merged = resolve.ensureResolved();

        assertEquals(2, merged.artifacts().size());
    }

    @Test
    void aFreshLockIsUsedWithoutResolvingAgain() throws Exception {
        Reporter reporter = mock(Reporter.class);
        ResolveContext resolve = seededProject(reporter).resolve();

        resolve.ensureResolved();

        verify(reporter, never()).info("resolve: fetching dependencies from Maven Central...");
    }
}
