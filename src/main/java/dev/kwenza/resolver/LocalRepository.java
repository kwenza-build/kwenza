package dev.kwenza.resolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The local cache, byte for byte Maven's own {@code ~/.m2/repository} layout, so kwenza shares it
 * with any Maven or Gradle install. Only ever written through {@link ArtifactStore}, which never
 * writes anything it hasn't verified. File-system errors throw: they're the environment failing,
 * not something a build can recover from.
 */
public final class LocalRepository {
    private final Path root;

    public LocalRepository(Path root) {
        this.root = root;
    }

    /** {@code root/com/example/leaf/1.0/leaf-1.0.<extension>}. */
    public Path path(Gav gav, String extension) {
        return root.resolve(gav.group().replace('.', '/')).resolve(gav.artifact()).resolve(gav.version())
                .resolve(gav.artifact() + "-" + gav.version() + "." + extension);
    }

    /** Writes via a temp file and an atomic move, so a reader never sees half a file. */
    void write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
