package dev.kwenza.commands;

import dev.kwenza.resolver.Resolver;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** `kwenza run` - builds, then executes [package].main-class in a fresh java subprocess. */
public final class RunCommand {
    private RunCommand() {
    }

    public static void run(BuildState state, boolean verbose) throws Exception {
        run(state, verbose, ProcessBuilder.Redirect.INHERIT);
    }

    /** @param outputRedirect INHERIT for real CLI use; tests pass DISCARD so a real nested subprocess's own console output never bleeds into the outer test run. */
    public static void run(BuildState state, boolean verbose, ProcessBuilder.Redirect outputRedirect) throws Exception {
        if (state.manifest().mainClass == null) {
            throw new CliException("kwenza.toml has no [package] main-class set - required for `kwenza run`");
        }
        Resolver.Resolution resolution = state.ensureCompiled();
        Path classes = state.targetDir().resolve("classes");

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
        command.add(state.manifest().mainClass);

        int depCount = resolution.classpath().size();
        String deps = depCount == 0 ? "" : " (+" + depCount + " dependency jar" + (depCount == 1 ? "" : "s") + ")";
        state.reporter().info("run: java -cp " + state.projectRoot().relativize(classes) + deps + " " + state.manifest().mainClass);
        if (verbose) {
            state.reporter().detail("  " + String.join(" ", command));
        }
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
