package dev.kwenza.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCompilationTest {

    @TempDir
    Path tempDir;

    @Test
    void compilingValidSourceProducesAClassFileAndNoDiagnostics() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("out");
        writeSource(sourceRoot, "com/example/Greeter.java", """
                package com.example;
                public final class Greeter {
                    public static String greet() { return "hello"; }
                }
                """);

        JavaCompilation.Result result = JavaCompilation.compile(sourceRoot, outputDir, List.of(), "21");

        assertTrue(result.success);
        assertTrue(result.diagnostics.isEmpty());
        assertTrue(Files.exists(outputDir.resolve("com/example/Greeter.class")));
    }

    @Test
    void aCompileErrorFailsWithADiagnostic() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("out");
        writeSource(sourceRoot, "com/example/Broken.java", """
                package com.example;
                public final class Broken {
                    public static String greet() { return 42; } // wrong type, deliberately
                }
                """);

        JavaCompilation.Result result = JavaCompilation.compile(sourceRoot, outputDir, List.of(), "21");

        assertFalse(result.success);
        assertFalse(result.diagnostics.isEmpty());
    }

    @Test
    void aMissingSourceRootIsTreatedAsNothingToCompileNotAnError() throws IOException {
        JavaCompilation.Result result = JavaCompilation.compile(
                tempDir.resolve("does-not-exist"), tempDir.resolve("out"), List.of(), "21");

        assertTrue(result.success);
        assertTrue(result.diagnostics.isEmpty());
    }

    @Test
    void anEmptySourceRootIsAlsoTreatedAsNothingToCompile() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        Files.createDirectories(sourceRoot);

        JavaCompilation.Result result = JavaCompilation.compile(sourceRoot, tempDir.resolve("out"), List.of(), "21");

        assertTrue(result.success);
    }

    @Test
    void compiledClassesCanReferenceAJarOnTheProvidedClasspath() throws IOException {
        // Compile a tiny "library" first, package it as a real jar, then compile a second
        // source root that references it purely via the classpath list - the real thing
        // JavaCompilation is for, not something worth mocking.
        Path libSourceRoot = tempDir.resolve("lib-src");
        Path libOut = tempDir.resolve("lib-out");
        writeSource(libSourceRoot, "com/example/Lib.java", """
                package com.example;
                public final class Lib {
                    public static int answer() { return 42; }
                }
                """);
        JavaCompilation.compile(libSourceRoot, libOut, List.of(), "21");
        Path libJar = tempDir.resolve("lib.jar");
        try (var jarStream = new java.util.jar.JarOutputStream(Files.newOutputStream(libJar))) {
            String entryName = "com/example/Lib.class";
            jarStream.putNextEntry(new java.util.jar.JarEntry(entryName));
            jarStream.write(Files.readAllBytes(libOut.resolve(entryName)));
            jarStream.closeEntry();
        }

        Path appSourceRoot = tempDir.resolve("app-src");
        Path appOut = tempDir.resolve("app-out");
        writeSource(appSourceRoot, "com/example/App.java", """
                package com.example;
                public final class App {
                    public static int run() { return Lib.answer(); }
                }
                """);

        JavaCompilation.Result result = JavaCompilation.compile(appSourceRoot, appOut, List.of(libJar), "21");

        assertTrue(result.success, () -> String.join("\n", result.diagnostics));
    }

    @Test
    void aJavaVersionNewerThanTheRunningJdkThrowsAnIncompatibleJdkException() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        writeSource(sourceRoot, "com/example/Anything.java", "package com.example; public final class Anything {}");

        assertFailsWithIncompatibleJdk(() ->
                JavaCompilation.compile(sourceRoot, tempDir.resolve("out"), List.of(), "9999"));
    }

    private static void assertFailsWithIncompatibleJdk(org.junit.jupiter.api.function.Executable executable) {
        org.junit.jupiter.api.Assertions.assertThrows(IncompatibleJdkException.class, executable);
    }

    private static void writeSource(Path sourceRoot, String relativePath, String content) throws IOException {
        Path file = sourceRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
