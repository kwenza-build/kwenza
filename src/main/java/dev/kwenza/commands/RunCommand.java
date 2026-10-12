package dev.kwenza.commands;

import dev.kwenza.compiler.CompileContext;
import dev.kwenza.context.KwenzaException;
import dev.kwenza.context.Reporter;
import dev.kwenza.resolver.Resolver;
import dev.kwenza.workspace.Project;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** {@code kwenza run}: builds, then executes [package].main-class in a fresh java subprocess. */
public final class RunCommand {
    private final Project project;
    private final CompileContext compile;
    private final Reporter reporter;

    public RunCommand(Project project, CompileContext compile, Reporter reporter) {
        this.project = project;
        this.compile = compile;
        this.reporter = reporter;
    }

    public void run() throws Exception {
        run(ProcessBuilder.Redirect.INHERIT);
    }

    /** @param outputRedirect INHERIT for real CLI use; tests pass DISCARD so a real nested subprocess's own console output never bleeds into the outer test run. */
    public void run(ProcessBuilder.Redirect outputRedirect) throws Exception {
        String mainClass = project.manifest().mainClass;
        if (mainClass == null) {
            throw new KwenzaException("kwenza.toml has no [package] main-class set - required for `kwenza run`");
        }
        Resolver.Resolution resolution = compile.ensureCompiled();
        Path classes = project.classesDir();

        List<String> classpathEntries = new ArrayList<>();
        classpathEntries.add(classes.toString());
        for (Path jar : resolution.classpath()) {
            classpathEntries.add(jar.toString());
        }
        String classpath = String.join(File.pathSeparator, classpathEntries);

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);

        int depCount = resolution.classpath().size();
        String deps = depCount == 0 ? "" : " (+" + depCount + " dependency jar" + (depCount == 1 ? "" : "s") + ")";
        reporter.info("run: java -cp " + project.relativize(classes) + deps + " " + mainClass);
        reporter.detail("  " + String.join(" ", command));
        Process process = new ProcessBuilder(command)
                .redirectOutput(outputRedirect)
                .redirectError(outputRedirect)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new CommandFailedException(exitCode);
        }
    }
}
