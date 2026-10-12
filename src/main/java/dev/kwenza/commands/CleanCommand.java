package dev.kwenza.commands;

import dev.kwenza.compiler.CompileContext;
import dev.kwenza.context.Reporter;
import dev.kwenza.workspace.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** {@code kwenza clean}: deletes the entire output directory. */
public final class CleanCommand {
    private final Project project;
    private final CompileContext compile;
    private final Reporter reporter;

    public CleanCommand(Project project, CompileContext compile, Reporter reporter) {
        this.project = project;
        this.compile = compile;
        this.reporter = reporter;
    }

    public void run() throws IOException {
        // Whatever was compiled before this point in the chain is gone now - a later
        // build/package/run in the same chain must recompile, not reuse stale state.
        compile.invalidate();
        Path outputDir = project.outputDir();
        if (!Files.exists(outputDir)) {
            reporter.info("clean: nothing to remove");
            return;
        }
        deleteRecursively(outputDir);
        reporter.info("clean: removed " + outputDir.getFileName() + "/");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
