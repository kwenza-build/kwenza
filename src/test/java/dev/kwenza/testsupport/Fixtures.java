package dev.kwenza.testsupport;

import dev.kwenza.commands.BuildCommand;
import dev.kwenza.commands.ResolveCommand;
import dev.kwenza.compiler.CompileContext;
import dev.kwenza.context.Reporter;
import dev.kwenza.manifest.KwenzaLock;
import dev.kwenza.manifest.KwenzaManifest;
import dev.kwenza.resolver.ArtifactStore;
import dev.kwenza.resolver.Gav;
import dev.kwenza.resolver.LocalRepository;
import dev.kwenza.resolver.MavenRepository;
import dev.kwenza.resolver.ResolveContext;
import dev.kwenza.resolver.Resolver;
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

    /**
     * One project, its shared state, and the commands that work on it, wired the way the CLI's
     * {@code Session} wires them. Commands are made fresh on each call, as they are there; the
     * state persists, which is what chained commands share.
     */
    public record Contexts(Project project, Reporter reporter, ResolveContext resolveState,
                           CompileContext compileState, Resolver resolver) {

        public ResolveCommand resolve() {
            return new ResolveCommand(project, resolveState, resolver, reporter);
        }

        public BuildCommand build() {
            return new BuildCommand(project, compileState, resolve(), reporter);
        }
    }

    public static Contexts contexts(Path projectRoot, Path localRepo, String manifestText) {
        return contexts(projectRoot, localRepo, manifestText, mock(Reporter.class), MavenRepository.central());
    }

    /** With a given reporter and remote repository, for tests that check what was reported or fetched. */
    public static Contexts contexts(Path projectRoot, Path localRepo, String manifestText, Reporter reporter,
                                    MavenRepository remote) {
        Project project = new Project(projectRoot, KwenzaManifest.parse(manifestText).get());
        Resolver resolver = new Resolver(new ArtifactStore(new LocalRepository(localRepo), remote));
        return new Contexts(project, reporter, new ResolveContext(), new CompileContext(), resolver);
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
            LocalRepository repo = new LocalRepository(localRepo);
            Gav gav = new Gav(group, artifact, version);
            Path pomPath = repo.path(gav, "pom");
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
            Path jarPath = repo.path(gav, "jar");
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
                new KwenzaLock(seeded, KwenzaLock.hashDependencies(project.manifest())).save(project.lockFile());
            }
        }
    }
}
