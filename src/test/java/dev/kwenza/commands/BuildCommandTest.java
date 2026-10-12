package dev.kwenza.commands;

import dev.kwenza.testsupport.Fixtures.Contexts;
import dev.kwenza.testsupport.Fixtures.SeededRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.kwenza.testsupport.Fixtures.contexts;
import static dev.kwenza.testsupport.Fixtures.writeSource;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real javac, real files, and real resolution against a pre-seeded local repo (see {@link SeededRepository}). */
class BuildCommandTest {

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

    private Contexts project(String manifestText) {
        return contexts(projectRoot(), tempDir.resolve("repo"), manifestText);
    }

    private void writeMain() throws Exception {
        writeSource(projectRoot(), "src/main/java/com/example/Main.java", """
                package com.example;
                public final class Main {
                    public static String greet() { return "hi"; }
                }
                """);
    }

    private Path mainClassFile() {
        return projectRoot().resolve("target/classes/com/example/Main.class");
    }

    @Test
    void compilesSrcMainJavaAndProducesNoArtifact() throws Exception {
        writeMain();

        project(NO_DEPENDENCIES).build().run().get();

        assertTrue(Files.exists(mainClassFile()));
        assertTrue(Files.notExists(projectRoot().resolve("target/my-app-0.1.0.jar")));
    }

    @Test
    void chainedCommandsCompileOnlyOnce() throws Exception {
        writeMain();
        Contexts state = project(NO_DEPENDENCIES);
        state.build().main().get();
        Files.delete(mainClassFile()); // if the second build recompiles, this file reappears

        state.build().main().get(); // a second command in the same chain, a fresh BuildCommand

        assertFalse(Files.exists(mainClassFile()), "recompiled even though nothing forgot the first compile");
    }

    @Test
    void forgettingTheCompileStateMakesTheNextBuildCompileAgain() throws Exception {
        writeMain();
        Contexts state = project(NO_DEPENDENCIES);
        state.build().main().get();
        Files.delete(mainClassFile());

        state.compileState().forget();
        state.build().main().get();

        assertTrue(Files.exists(mainClassFile()), "forget() should have made the next build compile again");
    }

    @Test
    void aCompileErrorIsAFailureNotAnException() throws Exception {
        writeSource(projectRoot(), "src/main/java/com/example/Broken.java", "package com.example; class Broken {");

        assertFalse(project(NO_DEPENDENCIES).build().run().isOk());
    }

    @Test
    void testsCompileAgainstMainClassesAndBothScopes() throws Exception {
        SeededRepository repo = new SeededRepository(tempDir.resolve("repo"));
        repo.seed("com.example", "dev-dep", "1.0");
        writeMain();
        writeSource(projectRoot(), "src/test/java/com/example/MainRef.java", """
                package com.example;
                public final class MainRef {
                    static { Main.greet(); } // references the compiled main output
                }
                """);
        Contexts state = project("""
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"

                [dev-dependencies]
                b = { module = "com.example:dev-dep", version = "1.0" }
                """);
        repo.lock(state.project());

        state.build().tests().get();

        assertTrue(Files.exists(projectRoot().resolve("target/test-classes/com/example/MainRef.class")));
    }
}
