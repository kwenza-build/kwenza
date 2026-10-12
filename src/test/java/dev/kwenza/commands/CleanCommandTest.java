package dev.kwenza.commands;

import dev.kwenza.testsupport.Fixtures.Contexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.kwenza.testsupport.Fixtures.contexts;
import static dev.kwenza.testsupport.Fixtures.writeSource;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanCommandTest {

    @Test
    void removesTheEntireOutputDirectory(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/main/java/com/example/Main.java",
                "package com.example; public final class Main {}");
        Contexts state = contexts(projectRoot, tempDir.resolve("repo"), """
                [package]
                group = "com.example"
                version = "0.1.0"
                """);
        new BuildCommand(state.compile()).run();
        assertTrue(Files.exists(projectRoot.resolve("target")));

        new CleanCommand(state.project(), state.compile(), state.reporter()).run();

        assertFalse(Files.exists(projectRoot.resolve("target")));
    }

    @Test
    void isANoOpRatherThanAnErrorWhenThereIsNothingToRemove(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Contexts state = contexts(projectRoot, tempDir.resolve("repo"), """
                [package]
                group = "com.example"
                version = "0.1.0"
                """);

        new CleanCommand(state.project(), state.compile(), state.reporter()).run(); // must not throw
    }

    @Test
    void invalidatesTheCompileContextSoANextCompileRedoesTheWork(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/main/java/com/example/Main.java",
                "package com.example; public final class Main {}");
        Contexts state = contexts(projectRoot, tempDir.resolve("repo"), """
                [package]
                group = "com.example"
                version = "0.1.0"
                """);
        new BuildCommand(state.compile()).run();
        Path classFile = projectRoot.resolve("target/classes/com/example/Main.class");

        new CleanCommand(state.project(), state.compile(), state.reporter()).run();
        new BuildCommand(state.compile()).run();

        assertTrue(Files.exists(classFile), "clean should have invalidated memoized compile state");
    }
}
