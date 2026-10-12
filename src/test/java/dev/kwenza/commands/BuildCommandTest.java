package dev.kwenza.commands;

import dev.kwenza.testsupport.Fixtures.Contexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.kwenza.testsupport.Fixtures.contexts;
import static dev.kwenza.testsupport.Fixtures.writeSource;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildCommandTest {

    @Test
    void compilesSrcMainJavaAndProducesNoArtifact(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        writeSource(projectRoot, "src/main/java/com/example/Main.java",
                "package com.example; public final class Main {}");
        Contexts state = contexts(projectRoot, tempDir.resolve("repo"), """
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                """);

        new BuildCommand(state.compile()).run().get();

        assertTrue(Files.exists(projectRoot.resolve("target/classes/com/example/Main.class")));
        assertTrue(Files.notExists(projectRoot.resolve("target/com.example-0.1.0.jar")));
    }
}
