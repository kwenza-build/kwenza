package dev.kwenza.commands;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.kwenza.commands.CommandTestSupport.newState;
import static dev.kwenza.commands.CommandTestSupport.writeSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("integration") // spawns a real OS subprocess, unlike everything else in this package
class RunCommandTest {

    @Test
    void withNoMainClassSetItFailsWithACliException(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/main/java/com/example/Main.java",
                "package com.example; public final class Main { public static void main(String[] a) {} }");
        BuildState state = newState(projectRoot, tempDir.resolve("repo"), """
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                """);

        assertThrows(CliException.class, () -> RunCommand.run(state, false));
    }

    @Test
    void aSuccessfulRunCompletesWithoutThrowing(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/main/java/com/example/Main.java",
                "package com.example; public final class Main { public static void main(String[] a) {} }");
        BuildState state = newState(projectRoot, tempDir.resolve("repo"), """
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                main-class = "com.example.Main"
                """);

        // DISCARD: this really does spawn a subprocess, and its own output must not bleed into
        // the outer test run's console (see TestCommandTest's class Javadoc for why).
        RunCommand.run(state, false, ProcessBuilder.Redirect.DISCARD); // must not throw
    }

    @Test
    void aNonZeroExitFromTheAppPropagatesAsACommandFailedException(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/main/java/com/example/Main.java",
                "package com.example; public final class Main { public static void main(String[] a) { System.exit(3); } }");
        BuildState state = newState(projectRoot, tempDir.resolve("repo"), """
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                main-class = "com.example.Main"
                """);

        CommandFailedException e = assertThrows(CommandFailedException.class,
                () -> RunCommand.run(state, false, ProcessBuilder.Redirect.DISCARD));

        assertEquals(3, e.exitCode);
    }
}
