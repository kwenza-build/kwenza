package dev.kwenza.commands;

import dev.kwenza.context.KwenzaException;
import dev.kwenza.testsupport.Fixtures.Contexts;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.kwenza.testsupport.Fixtures.contexts;
import static dev.kwenza.testsupport.Fixtures.writeSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("integration") // spawns a real OS subprocess, unlike everything else in this package
class RunCommandTest {

    @Test
    void withNoMainClassSetItFailsWithAKwenzaException(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/main/java/com/example/Main.java",
                "package com.example; public final class Main { public static void main(String[] a) {} }");
        Contexts state = contexts(projectRoot, tempDir.resolve("repo"), """
                [package]
                group = "com.example"
                version = "0.1.0"
                """);

        assertThrows(KwenzaException.class, () -> new RunCommand(state.project(), state.compile(), state.reporter()).run());
    }

    @Test
    void aSuccessfulRunCompletesWithoutThrowing(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/main/java/com/example/Main.java",
                "package com.example; public final class Main { public static void main(String[] a) {} }");
        Contexts state = contexts(projectRoot, tempDir.resolve("repo"), """
                [package]
                group = "com.example"
                version = "0.1.0"
                main-class = "com.example.Main"
                """);

        // DISCARD: this really does spawn a subprocess, and its own output must not bleed into
        // the outer test run's console (see TestCommandTest's class Javadoc for why).
        new RunCommand(state.project(), state.compile(), state.reporter()).run(ProcessBuilder.Redirect.DISCARD); // must not throw
    }

    @Test
    void aNonZeroExitFromTheAppPropagatesAsACommandFailedException(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/main/java/com/example/Main.java",
                "package com.example; public final class Main { public static void main(String[] a) { System.exit(3); } }");
        Contexts state = contexts(projectRoot, tempDir.resolve("repo"), """
                [package]
                group = "com.example"
                version = "0.1.0"
                main-class = "com.example.Main"
                """);

        CommandFailedException e = assertThrows(CommandFailedException.class,
                () -> new RunCommand(state.project(), state.compile(), state.reporter()).run(ProcessBuilder.Redirect.DISCARD));

        assertEquals(3, e.exitCode);
    }
}
