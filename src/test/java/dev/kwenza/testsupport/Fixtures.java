package dev.kwenza.testsupport;

import dev.kwenza.compiler.CompileContext;
import dev.kwenza.context.GlobalContext;
import dev.kwenza.context.Reporter;
import dev.kwenza.manifest.KwenzaLock;
import dev.kwenza.manifest.KwenzaManifest;
import dev.kwenza.resolver.MavenRepository;
import dev.kwenza.resolver.ResolveContext;
import dev.kwenza.workspace.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarOutputStream;

import static org.mockito.Mockito.mock;

/**
 * Fixture-building helpers shared by the tests of everything that takes a project and its
 * contexts: real temp projects and real collaborators throughout, except {@code Reporter}. That's
 * the one real external-facing boundary here (console output), so a mock is the right thing: its
 * void methods do nothing by default, so tests are silent, and {@code verify(...)} is there for
 * any test that cares what got reported.
 */
public final class Fixtures {
    private Fixtures() {
    }

    /** One project with every context wired the way the CLI's {@code Session} wires them. */
    public record Contexts(Project project, GlobalContext global, ResolveContext resolve, CompileContext compile) {
        public Reporter reporter() {
            return global.reporter();
        }
    }

    public static Contexts contexts(Path projectRoot, Path localRepo, String manifestText) {
        return contexts(projectRoot, localRepo, manifestText, mock(Reporter.class));
    }

    public static Contexts contexts(Path projectRoot, Path localRepo, String manifestText, Reporter reporter) {
        Project project = new Project(projectRoot, KwenzaManifest.parse(manifestText));
        GlobalContext global = new GlobalContext(projectRoot, localRepo, reporter);
        ResolveContext resolve = new ResolveContext(project, global);
        return new Contexts(project, global, resolve, new CompileContext(project, resolve, reporter));
    }

    public static void writeSource(Path root, String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** The real ~/.m2 (or KWENZA_REPO), for tests that need a real, already-cached artifact. */
    public static Path defaultLocalRepo() {
        String override = System.getenv("KWENZA_REPO");
        return override != null
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    /**
     * A local repository pre-seeded with POMs and (empty, valid) jars, plus a kwenza.lock pinning
     * each seeded jar's sha256, so the real resolve path runs with no network call. The lock is
     * what makes that possible: a jar nothing pins gets checked against the remote repository's
     * .sha1 even on a cache hit, and these artifacts don't exist there.
     */
    public static final class SeededRepository {
        private final Path localRepo;
        private final List<KwenzaLock.Entry> seeded = new ArrayList<>();

        public SeededRepository(Path localRepo) {
            this.localRepo = localRepo;
        }

        public Path path() {
            return localRepo;
        }

        public void seed(String group, String artifact, String version) throws Exception {
            MavenRepository repo = MavenRepository.central(localRepo);
            Path pomPath = repo.artifactPath(group, artifact, version, "pom");
            Files.createDirectories(pomPath.getParent());
            Files.writeString(pomPath, """
                    <project>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                    </project>
                    """.formatted(group, artifact, version));
            // A real, valid (if empty) jar - javac opens every classpath entry as a zip while
            // compiling, even ones the source under test doesn't actually reference.
            Path jarPath = repo.artifactPath(group, artifact, version, "jar");
            Files.createDirectories(jarPath.getParent());
            try (var jarOut = new JarOutputStream(Files.newOutputStream(jarPath))) {
                // no entries needed - an empty jar is still a valid one
            }
            String sha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jarPath)));
            seeded.add(new KwenzaLock.Entry(group + ":" + artifact, version, sha256, List.of("direct")));
        }

        /** Writes a kwenza.lock pinning everything seeded so far, fresh for {@code project}'s manifest. */
        public void lock(Project project) throws IOException {
            if (!seeded.isEmpty()) {
                new KwenzaLock(seeded, null).save(project.lockFile(), KwenzaLock.hashDependencies(project.manifest()));
            }
        }
    }
}
