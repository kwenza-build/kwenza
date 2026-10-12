package dev.kwenza.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.kwenza.commands.CommandTestSupport.newState;
import static dev.kwenza.commands.CommandTestSupport.writeSource;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanCommandTest {

    @Test
    void removesTheEntireOutputDirectory(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/main/java/com/example/Main.java",
                "package com.example; public final class Main {}");
        BuildState state = newState(projectRoot, tempDir.resolve("repo"), """
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                """);
        BuildCommand.run(state);
        assertTrue(Files.exists(projectRoot.resolve("target")));

        CleanCommand.run(state);

        assertFalse(Files.exists(projectRoot.resolve("target")));
    }

    @Test
    void isANoOpRatherThanAnErrorWhenThereIsNothingToRemove(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        BuildState state = newState(projectRoot, tempDir.resolve("repo"), """
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                """);

        CleanCommand.run(state); // must not throw
    }

    @Test
    void invalidatesTheBuildStateSoANextCompileRedoesTheWork(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/main/java/com/example/Main.java",
                "package com.example; public final class Main {}");
        BuildState state = newState(projectRoot, tempDir.resolve("repo"), """
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                """);
        BuildCommand.run(state);
        Path classFile = projectRoot.resolve("target/classes/com/example/Main.class");

        CleanCommand.run(state);
        BuildCommand.run(state);

        assertTrue(Files.exists(classFile), "clean should have invalidated memoized compile state");
    }
}
