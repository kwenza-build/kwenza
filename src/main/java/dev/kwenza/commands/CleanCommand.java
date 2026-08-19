package dev.kwenza.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** `kwenza clean` - deletes the entire output directory. */
public final class CleanCommand {
    private CleanCommand() {
    }

    public static void run(BuildState state) throws IOException {
        // Whatever was compiled before this point in the chain is gone now - a later
        // build/package/run in the same chain must recompile, not reuse stale state.
        state.invalidate();
        Path targetDir = state.targetDir();
        if (!Files.exists(targetDir)) {
            state.reporter().info("clean: nothing to remove");
            return;
        }
        deleteRecursively(targetDir);
        state.reporter().info("clean: removed " + targetDir.getFileName() + "/");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
