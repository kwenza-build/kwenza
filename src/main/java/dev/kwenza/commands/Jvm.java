package dev.kwenza.commands;

import dev.kwenza.context.Failure;
import dev.kwenza.context.Reporter;
import dev.kwenza.context.Result;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Forks a JVM the way every kwenza command that runs one does: status line first, the full
 * command as {@code --verbose} detail, the same JDK kwenza runs on, both output streams to
 * {@code output}, and a non-zero exit returned as {@link Failure#child} (the child has already
 * said what went wrong). Forking at all, rather than running in kwenza's own JVM, is deliberate:
 * see AGENTS.md, "`kwenza test` forks a JVM".
 */
final class Jvm {
    private final Path projectRoot;
    private final Reporter reporter;
    private final ProcessBuilder.Redirect output;

    /** @param output INHERIT for real CLI use; tests pass DISCARD so the child's output never bleeds into theirs. */
    Jvm(Path projectRoot, Reporter reporter, ProcessBuilder.Redirect output) {
        this.projectRoot = projectRoot;
        this.reporter = reporter;
        this.output = output;
    }

    Result<Void> run(JvmLaunch launch) throws IOException, InterruptedException {
        List<String> command = launch.command(Path.of(System.getProperty("java.home"), "bin", "java"));
        reporter.info(launch.statusLine(projectRoot));
        reporter.detail("  " + String.join(" ", command));
        Process process = new ProcessBuilder(command)
                .redirectOutput(output)
                .redirectError(output)
                .start();
        int exitCode = process.waitFor();
        return exitCode == 0 ? Result.ok() : Result.err(Failure.child(exitCode));
    }
}
