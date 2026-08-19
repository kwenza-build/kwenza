package dev.kwenza.commands;

import dev.kwenza.manifest.KwenzaManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.mock;

/**
 * Shared fixture-building helpers for the *CommandTest classes - real temp projects, real
 * collaborators throughout, except {@code Reporter}: that's the one real external-facing
 * boundary here (console output), so a mock is the right thing, not a real console
 * implementation. A mock's void methods do nothing by default, which is exactly what's wanted -
 * silent by default, with {@code verify(...)} available to any test that cares what got reported.
 */
final class CommandTestSupport {
    private CommandTestSupport() {
    }

    static BuildState newState(Path projectRoot, Path localRepo, String manifestText) {
        return newState(projectRoot, localRepo, manifestText, mock(Reporter.class));
    }

    static BuildState newState(Path projectRoot, Path localRepo, String manifestText, Reporter reporter) {
        KwenzaManifest manifest = KwenzaManifest.parse(manifestText, "project");
        return new BuildState(manifest, projectRoot, projectRoot.resolve("kwenza.lock"),
                projectRoot.resolve("target"), localRepo, false, false, reporter);
    }

    static void writeSource(Path root, String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
