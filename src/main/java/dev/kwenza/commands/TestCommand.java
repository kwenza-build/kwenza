package dev.kwenza.commands;

import dev.kwenza.compiler.CompileContext;
import dev.kwenza.context.Failure;
import dev.kwenza.context.Reporter;
import dev.kwenza.context.Result;
import dev.kwenza.resolver.Resolver;
import dev.kwenza.workspace.Project;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** {@code kwenza test}: builds, compiles src/test/java, and runs it via the JUnit Platform Console Launcher. */
public final class TestCommand {
    private final Project project;
    private final CompileContext compile;
    private final Reporter reporter;

    public TestCommand(Project project, CompileContext compile, Reporter reporter) {
        this.project = project;
        this.compile = compile;
        this.reporter = reporter;
    }

    public Result<Void> run() throws IOException, InterruptedException {
        return run(ProcessBuilder.Redirect.INHERIT);
    }

    /** @param outputRedirect INHERIT for real CLI use; tests pass DISCARD so a real nested ConsoleLauncher's own console report never bleeds into the outer test run. */
    public Result<Void> run(ProcessBuilder.Redirect outputRedirect) throws IOException, InterruptedException {
        Result<Resolver.Resolution> compiled = compile.ensureTestCompiled();
        if (compiled instanceof Result.Err<?> err) {
            return err.castErr();
        }
        Resolver.Resolution resolution = compiled.get();
        Path classes = project.classesDir();
        Path testClasses = project.testClassesDir();

        if (!hasAnyCompiledTestClasses(testClasses)) {
            // Nothing to run - same "empty is not an error" behavior as an empty src/main/java
            // under build. Skip spawning a subprocess entirely: doing so with no dev-dependency
            // providing ConsoleLauncher (the common case for a project with no tests at all, e.g.
            // examples/hello-world) would otherwise fail with a raw ClassNotFoundException rather
            // than this clear message.
            reporter.info("test: no tests found");
            return Result.ok();
        }
        if (!hasConsoleLauncher(resolution)) {
            return Result.err("src/test/java has test sources, but no dev-dependency provides "
                    + "org.junit.platform.console.ConsoleLauncher - add "
                    + "org.junit.platform:junit-platform-console-standalone to [dev-dependencies] "
                    + "(see https://kwenza.dev/docs/manifest-reference.html)");
        }

        List<String> classpathEntries = new ArrayList<>();
        classpathEntries.add(classes.toString());
        classpathEntries.add(testClasses.toString());
        for (Path jar : resolution.classpath()) {
            classpathEntries.add(jar.toString());
        }
        String classpath = String.join(File.pathSeparator, classpathEntries);

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        // If mockito-core is a resolved dev-dependency, load it as an explicit agent rather than
        // letting it self-attach after the JVM has already started: self-attach already warns on
        // JDK21+ and Mockito's own docs (linked from that warning) say dynamic loading will stop
        // working in a future JDK release. mockito-core's own jar is a valid agent jar (its
        // manifest declares Premain-Class), so no separate agent artifact is needed.
        mockitoAgentJar(resolution).ifPresent(jar -> command.add("-javaagent:" + jar));
        command.add("-cp");
        command.add(classpath);
        command.add("org.junit.platform.console.ConsoleLauncher");
        command.add("execute");
        command.add("--scan-classpath=" + testClasses);
        command.add("--disable-banner");

        int depCount = resolution.classpath().size();
        String deps = depCount == 0 ? "" : " (+" + depCount + " dependency jar" + (depCount == 1 ? "" : "s") + ")";
        reporter.info("test: java -cp " + project.relativize(classes) + ":"
                + project.relativize(testClasses) + deps + " ConsoleLauncher");
        reporter.detail("  " + String.join(" ", command));
        // A forked JVM, not an in-process call into the launcher API: keeps kwenza itself free of
        // any JUnit/Mockito classes (no compile-time dependency, nothing to reflect against), and
        // isolates kwenza's own process from a test calling System.exit() or Mockito's inline mock
        // maker self-attaching a Java agent - the same reason Surefire and Gradle's test workers fork.
        Process process = new ProcessBuilder(command)
                .redirectOutput(outputRedirect)
                .redirectError(outputRedirect)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            // Failing tests are the launcher's own outcome: it has reported them, kwenza passes its code on.
            reporter.error("test: failed (exit " + exitCode + ")");
            return Result.err(Failure.child(exitCode));
        }
        reporter.info("test: passed");
        return Result.ok();
    }

    private static Optional<Path> mockitoAgentJar(Resolver.Resolution resolution) {
        for (Resolver.ResolvedArtifact artifact : resolution.artifacts()) {
            if (artifact.moduleId().equals("org.mockito:mockito-core")) {
                return Optional.of(artifact.jarPath());
            }
        }
        return Optional.empty();
    }

    private static boolean hasAnyCompiledTestClasses(Path testClasses) throws IOException {
        if (!Files.isDirectory(testClasses)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(testClasses)) {
            return walk.anyMatch(p -> p.toString().endsWith(".class"));
        }
    }

    private static boolean hasConsoleLauncher(Resolver.Resolution resolution) {
        for (Resolver.ResolvedArtifact artifact : resolution.artifacts()) {
            if (artifact.moduleId().equals("org.junit.platform:junit-platform-console-standalone")) {
                return true;
            }
        }
        return false;
    }
}
