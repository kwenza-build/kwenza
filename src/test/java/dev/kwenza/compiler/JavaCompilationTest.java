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

        JavaCompilation.Output result = JavaCompilation.compile(sourceRoot, outputDir, List.of(), "21").get();

        assertTrue(result.success());
        assertTrue(result.diagnostics().isEmpty());
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

        JavaCompilation.Output result = JavaCompilation.compile(sourceRoot, outputDir, List.of(), "21").get();

        assertFalse(result.success());
        assertFalse(result.diagnostics().isEmpty());
    }

    @Test
    void aMissingSourceRootIsTreatedAsNothingToCompileNotAnError() throws IOException {
        JavaCompilation.Output result = JavaCompilation.compile(
                tempDir.resolve("does-not-exist"), tempDir.resolve("out"), List.of(), "21").get();

        assertTrue(result.success());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void anEmptySourceRootIsAlsoTreatedAsNothingToCompile() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        Files.createDirectories(sourceRoot);

        JavaCompilation.Output result = JavaCompilation.compile(sourceRoot, tempDir.resolve("out"), List.of(), "21").get();

        assertTrue(result.success());
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
        JavaCompilation.compile(libSourceRoot, libOut, List.of(), "21").get();
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

        JavaCompilation.Output result = JavaCompilation.compile(appSourceRoot, appOut, List.of(libJar), "21").get();

        assertTrue(result.success(), () -> String.join("\n", result.diagnostics()));
    }

    @Test
    void anAnnotationProcessorOnTheClasspathIsNotRun() throws IOException {
        // A real processor, registered the standard ServiceLoader way, that generates a class
        // for every compilation round it sees. JDK 21's javac would discover and run it from the
        // classpath on its own (JDK 23+ wouldn't); kwenza must not, on any JDK.
        Path processorSourceRoot = tempDir.resolve("processor-src");
        Path processorOut = tempDir.resolve("processor-out");
        writeSource(processorSourceRoot, "com/example/proc/GeneratingProcessor.java", """
                package com.example.proc;
                import java.io.IOException;
                import java.io.UncheckedIOException;
                import java.io.Writer;
                import java.util.Set;
                import javax.annotation.processing.AbstractProcessor;
                import javax.annotation.processing.RoundEnvironment;
                import javax.annotation.processing.SupportedAnnotationTypes;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                @SupportedAnnotationTypes("*")
                public final class GeneratingProcessor extends AbstractProcessor {
                    private boolean generated;
                    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (generated) return false;
                        generated = true;
                        try (Writer w = processingEnv.getFiler().createSourceFile("com.example.Generated").openWriter()) {
                            w.write("package com.example; public final class Generated {}");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        return false;
                    }
                }
                """);
        JavaCompilation.compile(processorSourceRoot, processorOut, List.of(), "21").get();
        Path processorJar = tempDir.resolve("processor.jar");
        try (var jarStream = new java.util.jar.JarOutputStream(Files.newOutputStream(processorJar))) {
            String classEntry = "com/example/proc/GeneratingProcessor.class";
            jarStream.putNextEntry(new java.util.jar.JarEntry(classEntry));
            jarStream.write(Files.readAllBytes(processorOut.resolve(classEntry)));
            jarStream.closeEntry();
            jarStream.putNextEntry(new java.util.jar.JarEntry("META-INF/services/javax.annotation.processing.Processor"));
            jarStream.write("com.example.proc.GeneratingProcessor\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jarStream.closeEntry();
        }

        Path appSourceRoot = tempDir.resolve("app-src");
        Path appOut = tempDir.resolve("app-out");
        writeSource(appSourceRoot, "com/example/App.java", "package com.example; public final class App {}");

        JavaCompilation.Output result = JavaCompilation.compile(appSourceRoot, appOut, List.of(processorJar), "21").get();

        assertTrue(result.success(), () -> String.join("\n", result.diagnostics()));
        assertTrue(Files.exists(appOut.resolve("com/example/App.class")));
        assertFalse(Files.exists(appOut.resolve("com/example/Generated.class")),
                "the classpath processor ran and generated com.example.Generated");
    }

    @Test
    void aJavaVersionNewerThanTheRunningJdkIsAFailureSayingHowToFixIt() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        writeSource(sourceRoot, "com/example/Anything.java", "package com.example; public final class Anything {}");

        String problem = JavaCompilation.compile(sourceRoot, tempDir.resolve("out"), List.of(), "9999")
                .failure().problems().get(0);

        assertTrue(problem.contains("java-version"), problem);
    }

    private static void writeSource(Path sourceRoot, String relativePath, String content) throws IOException {
        Path file = sourceRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
