package dev.kwenza.context;

import java.nio.file.Path;

/**
 * Everything about the environment kwenza runs in that isn't about a particular project: where
 * it was invoked from, which local dependency cache to use, and where to report progress. One per
 * invocation, created by the CLI and handed to whatever needs it.
 *
 * @param workingDir the directory kwenza was invoked from
 * @param localRepo  the Maven-layout dependency cache ({@code ~/.m2/repository}, or {@code KWENZA_REPO})
 * @param reporter   where progress and errors go
 */
public record GlobalContext(Path workingDir, Path localRepo, Reporter reporter) {

    /** Reads the real process environment: the current directory, {@code KWENZA_REPO} and {@code user.home}. */
    public static GlobalContext fromEnvironment(Reporter reporter) {
        String repoOverride = System.getenv("KWENZA_REPO");
        Path localRepo = repoOverride != null
                ? Path.of(repoOverride)
                : Path.of(System.getProperty("user.home"), ".m2", "repository");
        return new GlobalContext(Path.of("").toAbsolutePath(), localRepo, reporter);
    }
}
