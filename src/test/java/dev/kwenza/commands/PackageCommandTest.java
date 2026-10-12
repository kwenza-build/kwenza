package dev.kwenza.commands;

import dev.kwenza.testsupport.Fixtures.Contexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static dev.kwenza.testsupport.Fixtures.contexts;
import static dev.kwenza.testsupport.Fixtures.writeSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageCommandTest {

    @Test
    void producesAJarNamedAfterTheManifestWithItsMainClassAndVersionSet(@TempDir Path tempDir) throws Exception {
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

        Path outputJar = new PackageCommand(state.project(), state.compile(), state.reporter()).run().get();

        assertEquals(projectRoot.resolve("target/my-app-0.1.0.jar"), outputJar);
        try (JarFile jar = new JarFile(outputJar.toFile())) {
            assertEquals("com.example.Main", jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertEquals("my-app", jar.getManifest().getMainAttributes().getValue("Implementation-Title"));
            assertEquals("0.1.0", jar.getManifest().getMainAttributes().getValue("Implementation-Version"));
            assertTrue(jar.getEntry("com/example/Main.class") != null);
        }
    }
}
