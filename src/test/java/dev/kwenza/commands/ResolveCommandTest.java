package dev.kwenza.commands;

import dev.kwenza.testsupport.Fixtures.Contexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.kwenza.testsupport.Fixtures.contexts;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolveCommandTest {

    @Test
    void writesKwenzaLockWithoutCompilingAnything(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Contexts state = contexts(projectRoot, tempDir.resolve("repo"), """
                [package]
                group = "com.example"
                version = "0.1.0"
                """);

        new ResolveCommand(state.resolve()).run();

        assertTrue(Files.exists(projectRoot.resolve("kwenza.lock")));
        assertTrue(Files.notExists(projectRoot.resolve("target")));
    }
}
