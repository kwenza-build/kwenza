package dev.kwenza.compiler;

import dev.kwenza.testsupport.Fixtures;
import dev.kwenza.testsupport.Fixtures.Contexts;
import dev.kwenza.testsupport.Fixtures.SeededRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.kwenza.testsupport.Fixtures.writeSource;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real javac, real files, and a real resolver against a pre-seeded local repo (see {@link SeededRepository}). */
class CompileContextTest {

    private static final String NO_DEPENDENCIES = """
            [package]
            name = "my-app"
            group = "com.example"
            version = "0.1.0"
            """;

    @TempDir
    Path tempDir;

    private Path projectRoot() {
        return tempDir.resolve("project");
    }

    private CompileContext compileContext(String manifestText) {
        return Fixtures.contexts(projectRoot(), tempDir.resolve("repo"), manifestText).compile();
    }

    private void writeMain() throws Exception {
        writeSource(projectRoot(), "src/main/java/com/example/Main.java", """
                package com.example;
                public final class Main {
                    public static String greet() { return "hi"; }
                }
                """);
    }

    @Test
    void ensureCompiledProducesClassFilesFromSrcMainJava() throws Exception {
        writeMain();

        compileContext(NO_DEPENDENCIES).ensureCompiled().get();

        assertTrue(Files.exists(projectRoot().resolve("target/classes/com/example/Main.class")));
    }

    @Test
    void ensureCompiledCalledTwiceDoesNotRecompile() throws Exception {
        writeMain();
        CompileContext compile = compileContext(NO_DEPENDENCIES);
        compile.ensureCompiled().get();
        Path classFile = projectRoot().resolve("target/classes/com/example/Main.class");
        assertTrue(Files.exists(classFile));
        Files.delete(classFile); // if the second call recompiles, this file reappears

        compile.ensureCompiled().get();

        assertFalse(Files.exists(classFile), "ensureCompiled() recompiled even though nothing invalidated it");
    }

    @Test
    void invalidateMakesTheNextEnsureCompiledActuallyRecompile() throws Exception {
        writeMain();
        CompileContext compile = compileContext(NO_DEPENDENCIES);
        compile.ensureCompiled().get();
        Path classFile = projectRoot().resolve("target/classes/com/example/Main.class");
        Files.delete(classFile);
        compile.invalidate();

        compile.ensureCompiled().get();

        assertTrue(Files.exists(classFile), "invalidate() should have made ensureCompiled() recompile");
    }

    @Test
    void ensureTestCompiledPutsBothMainAndTestDependenciesOnTheTestCompileClasspath() throws Exception {
        SeededRepository repo = new SeededRepository(tempDir.resolve("repo"));
        repo.seed("com.example", "dev-dep", "1.0");
        writeMain();
        writeSource(projectRoot(), "src/test/java/com/example/MainRef.java", """
                package com.example;
                public final class MainRef {
                    static { Main.greet(); } // references the compiled main output
                }
                """);
        Contexts contexts = Fixtures.contexts(projectRoot(), repo.path(), """
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"

                [dev-dependencies]
                b = { module = "com.example:dev-dep", version = "1.0" }
                """);
        repo.lock(contexts.project());

        contexts.compile().ensureTestCompiled().get();

        assertTrue(Files.exists(projectRoot().resolve("target/test-classes/com/example/MainRef.class")));
    }
}
