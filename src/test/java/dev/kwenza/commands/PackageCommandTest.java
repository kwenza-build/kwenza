package dev.kwenza.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static dev.kwenza.commands.CommandTestSupport.newState;
import static dev.kwenza.commands.CommandTestSupport.writeSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageCommandTest {

    @Test
    void producesAJarNamedAfterTheManifestWithTheMainClassSet(@TempDir Path tempDir) throws Exception {
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

        Path outputJar = PackageCommand.run(state);

        assertEquals(projectRoot.resolve("target/my-app-0.1.0.jar"), outputJar);
        try (JarFile jar = new JarFile(outputJar.toFile())) {
            assertEquals("com.example.Main", jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertTrue(jar.getEntry("com/example/Main.class") != null);
        }
    }
}
