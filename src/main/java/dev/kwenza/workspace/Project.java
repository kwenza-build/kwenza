package dev.kwenza.workspace;

import dev.kwenza.context.Result;
import dev.kwenza.manifest.KwenzaManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One kwenza project: its manifest, its root directory, and every path kwenza reads from or
 * writes to inside it. The one place that knows the on-disk layout ({@code src/main/java},
 * {@code <output-dir>/classes}, the jar's file name, ...), so no command has to rebuild those
 * paths itself. Owning the root explicitly, rather than assuming the working directory, is what
 * lets a future {@code --project <path>} just load a different root.
 */
public final class Project {
    public static final String MANIFEST_FILE = "kwenza.toml";
    public static final String LOCK_FILE = "kwenza.lock";

    private final Path root;
    private final KwenzaManifest manifest;

    public Project(Path root, KwenzaManifest manifest) {
        this.root = root;
        this.manifest = manifest;
    }

    /**
     * The project whose {@code kwenza.toml} sits directly in {@code root}, or a failure if there's
     * no manifest there or it's invalid (prefixed with the file name, so the user knows where to look).
     */
    public static Result<Project> load(Path root) throws IOException {
        Path manifestPath = root.resolve(MANIFEST_FILE);
        if (!Files.exists(manifestPath)) {
            return Result.err("No " + MANIFEST_FILE + " found in " + root);
        }
        Result<KwenzaManifest> manifest = KwenzaManifest.load(manifestPath);
        if (manifest instanceof Result.Err<?> err) {
            return Result.err(MANIFEST_FILE + ": " + err.failure().problems().get(0));
        }
        return Result.ok(new Project(root, manifest.get()));
    }

    public Path root() {
        return root;
    }

    public KwenzaManifest manifest() {
        return manifest;
    }

    public Path lockFile() {
        return root.resolve(LOCK_FILE);
    }

    public Path mainSources() {
        return root.resolve("src/main/java");
    }

    public Path mainResources() {
        return root.resolve("src/main/resources");
    }

    public Path testSources() {
        return root.resolve("src/test/java");
    }

    public Path testResources() {
        return root.resolve("src/test/resources");
    }

    /** {@code [package] output-dir}, {@code target} by default. */
    public Path outputDir() {
        return root.resolve(manifest.outputDir);
    }

    public Path classesDir() {
        return outputDir().resolve("classes");
    }

    public Path testClassesDir() {
        return outputDir().resolve("test-classes");
    }

    /** {@code <output-dir>/<name>-<version>.jar}. */
    public Path jarFile() {
        return outputDir().resolve(manifest.name + "-" + manifest.version + ".jar");
    }

    /** {@code path} relative to the project root, for status lines. */
    public Path relativize(Path path) {
        return root.relativize(path);
    }
}
