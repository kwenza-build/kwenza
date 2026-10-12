package dev.kwenza.commands;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.kwenza.commands.CommandTestSupport.newState;
import static dev.kwenza.commands.CommandTestSupport.writeSource;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uses the real, shared ~/.m2 cache (like Main.java's own default) rather than an isolated repo:
 * junit-platform-console-standalone is already warm there by the time this runs, since resolving
 * kwenza's own dev-dependencies is a prerequisite of `kwenza test` running this test at all. No
 * network call results from that - it's a guaranteed-warm local cache hit, not a live fetch.
 *
 * <p>These tests genuinely spawn a real ConsoleLauncher subprocess - the same real invocation a
 * user gets from `kwenza test` - but pass {@code ProcessBuilder.Redirect.DISCARD} rather than the
 * production {@code INHERIT}, so the fixture subprocess's own console report never bleeds into
 * kwenza's own outer test run. Names stay unmistakable ("FixtureDeliberatelyFailingTest") anyway,
 * on the chance anyone re-enables INHERIT locally while debugging this test itself.
 */
@Tag("integration") // spawns a real OS subprocess, unlike everything else in this package
class TestCommandTest {

    private static Path defaultLocalRepo() {
        String override = System.getenv("KWENZA_REPO");
        return override != null
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    private static final String MANIFEST = """
            [package]
            name = "my-app"
            group = "com.example"
            version = "0.1.0"

            [dev-dependencies]
            junit-console = { module = "org.junit.platform:junit-platform-console-standalone", version = "6.1.3" }
            """;

    @Test
    void aPassingTestSuiteCompletesWithoutThrowing(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/test/java/com/example/FixturePassingTest.java", """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class FixturePassingTest {
                    @Test void arithmeticStillWorks() { assertEquals(2, 1 + 1); }
                }
                """);
        BuildState state = newState(projectRoot, defaultLocalRepo(), MANIFEST);

        // DISCARD: this really does spawn a ConsoleLauncher subprocess, and its own console
        // report must not bleed into the outer test run's console (see the class Javadoc above).
        TestCommand.run(state, false, ProcessBuilder.Redirect.DISCARD); // must not throw
    }

    @Test
    void aFailingTestSuitePropagatesAsACommandFailedException(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        // Deliberately named to be unmistakable, even though its output is discarded (see the
        // class Javadoc above) - the name alone should be enough if this ever appears in a report.
        writeSource(projectRoot, "src/test/java/com/example/FixtureDeliberatelyFailingTest.java", """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class FixtureDeliberatelyFailingTest {
                    @Test void thisFailureIsExpectedNotARealBug() { assertEquals(99, 1 + 1); }
                }
                """);
        BuildState state = newState(projectRoot, defaultLocalRepo(), MANIFEST);

        assertThrows(CommandFailedException.class,
                () -> TestCommand.run(state, false, ProcessBuilder.Redirect.DISCARD));
    }

    @Test
    void noTestSourcesAndNoConsoleLauncherDependencyReportsNoTestsFoundRatherThanFailing(
            @TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot); // no src/test/java at all - examples/hello-world's exact shape
        BuildState state = newState(projectRoot, defaultLocalRepo(), """
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                """); // no [dev-dependencies] at all - nothing provides ConsoleLauncher either

        // Must not throw: with nothing to run, whether a launcher is even resolvable is
        // irrelevant - this is the exact bug examples/hello-world hit (a raw
        // ClassNotFoundException instead of a clean no-op) before the check below existed.
        TestCommand.run(state, false, ProcessBuilder.Redirect.DISCARD);
    }

    @Test
    void testSourcesWithNoConsoleLauncherDependencyFailsWithAClearCliException(@TempDir Path tempDir)
            throws Exception {
        Path projectRoot = tempDir.resolve("project");
        // Deliberately doesn't reference any JUnit API: this manifest declares no
        // dev-dependencies at all, so a real @Test-annotated class wouldn't even compile. The
        // point here is exercising the "no dev-dependency provides ConsoleLauncher" check
        // specifically, once real test classes exist - not compilation, covered elsewhere.
        writeSource(projectRoot, "src/test/java/com/example/SomeTest.java",
                "package com.example; class SomeTest {}");
        BuildState state = newState(projectRoot, defaultLocalRepo(), """
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                """);

        CliException e = assertThrows(CliException.class,
                () -> TestCommand.run(state, false, ProcessBuilder.Redirect.DISCARD));

        assertTrue(e.getMessage().contains("junit-platform-console-standalone"), e.getMessage());
    }
}
