package dev.kwenza.commands;

import dev.kwenza.context.Failure;
import dev.kwenza.context.Result;
import dev.kwenza.testsupport.Fixtures.Contexts;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.kwenza.testsupport.Fixtures.contexts;
import static dev.kwenza.testsupport.Fixtures.writeSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration") // spawns a real OS subprocess, unlike everything else in this package
class RunCommandTest {

    @Test
    void withNoMainClassSetItFailsNamingTheMissingField(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/main/java/com/example/Main.java",
                "package com.example; public final class Main { public static void main(String[] a) {} }");
        Contexts state = contexts(projectRoot, tempDir.resolve("repo"), """
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                """);

        Result<Void> result = new RunCommand(state.project(), state.build(), state.reporter()).run();

        assertTrue(result.failure().problems().get(0).contains("main-class"), result.failure().toString());
    }

    @Test
    void aSuccessfulRunSucceeds(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/main/java/com/example/Main.java",
                "package com.example; public final class Main { public static void main(String[] a) {} }");
        Contexts state = contexts(projectRoot, tempDir.resolve("repo"), """
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                main-class = "com.example.Main"
                """);

        // DISCARD: this really does spawn a subprocess, and its own output must not bleed into
        // the outer test run's console (see TestCommandTest's class Javadoc for why).
        assertTrue(new RunCommand(state.project(), state.build(), state.reporter())
                .run(ProcessBuilder.Redirect.DISCARD).isOk());
    }

    @Test
    void aNonZeroExitFromTheAppIsPassedThroughAsItsExitCode(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/main/java/com/example/Main.java",
                "package com.example; public final class Main { public static void main(String[] a) { System.exit(3); } }");
        Contexts state = contexts(projectRoot, tempDir.resolve("repo"), """
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                main-class = "com.example.Main"
                """);

        Result<Void> result = new RunCommand(state.project(), state.build(), state.reporter())
                .run(ProcessBuilder.Redirect.DISCARD);

        assertEquals(Failure.child(3), result.failure());
    }
}
