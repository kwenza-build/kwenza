package dev.kwenza.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.kwenza.commands.CommandTestSupport.newState;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolveCommandTest {

    @Test
    void writesKwenzaLockWithoutCompilingAnything(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        BuildState state = newState(projectRoot, tempDir.resolve("repo"), """
                [package]
                group = "com.example"
                version = "0.1.0"
                """);

        ResolveCommand.run(state);

        assertTrue(Files.exists(projectRoot.resolve("kwenza.lock")));
        assertTrue(Files.notExists(projectRoot.resolve("target")));
    }
}
