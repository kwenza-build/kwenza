package dev.kwenza.commands;

import dev.kwenza.compiler.JavaCompilation;
import dev.kwenza.manifest.Dependency;
import dev.kwenza.manifest.KwenzaLock;
import dev.kwenza.manifest.KwenzaManifest;
import dev.kwenza.resolver.MavenRepository;
import dev.kwenza.resolver.Resolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A single invocation's resolution/compilation state, memoized so a command chain (e.g.
 * `kwenza build run`) resolves and compiles exactly once, not once per command that happens to
 * need it. One instance per `kwenza` invocation, shared across every command in its chain.
 */
public final class BuildState {

    private final KwenzaManifest manifest;
    private final Path projectRoot;
    private final Path lockPath;
    private final Path targetDir;
    private final Path localRepo;
    private final boolean update;
    private final boolean verbose;
    private final Reporter reporter;

    private Resolver.Resolution resolution;      // dependencies + dev-dependencies merged, backed by kwenza.lock
    private Resolver.Resolution mainResolution;  // dependencies only - derived locally, never hits the network on its own
    private boolean compiled;
    private boolean testCompiled;

    public BuildState(KwenzaManifest manifest, Path projectRoot, Path lockPath, Path targetDir,
                       Path localRepo, boolean update, boolean verbose, Reporter reporter) {
        this.manifest = manifest;
        this.projectRoot = projectRoot;
        this.lockPath = lockPath;
        this.targetDir = targetDir;
        this.localRepo = localRepo;
        this.update = update;
        this.verbose = verbose;
        this.reporter = reporter;
    }

    public KwenzaManifest manifest() {
        return manifest;
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path targetDir() {
        return targetDir;
    }

    public Reporter reporter() {
        return reporter;
    }

    /**
     * Whatever was compiled before this point is gone now - the next ensureCompiled/
     * ensureTestCompiled call in the chain must redo it, not reuse stale state.
     */
    public void invalidate() {
        compiled = false;
        testCompiled = false;
    }

    /** Resolves at most once per invocation - later calls in the same command chain reuse the result. */
    public Resolver.Resolution ensureResolved() throws IOException {
        if (resolution == null) {
            resolution = resolve();
        }
        return resolution;
    }

    private Resolver.Resolution resolve() throws IOException {
        MavenRepository repo = MavenRepository.central(localRepo);
        Resolver resolver = new Resolver(repo);
        KwenzaLock existingLock = KwenzaLock.load(lockPath);

        if (!update && existingLock.isFreshFor(manifest)) {
            if (!existingLock.entries.isEmpty()) {
                reporter.info("resolve: using locked dependencies (kwenza.lock)");
            }
            Resolver.Resolution loaded = resolver.ensureFromLock(existingLock);
            Timing.mark("resolved (from lock)");
            return loaded;
        }

        reporter.info("resolve: fetching dependencies from Maven Central...");
        List<Dependency> allDeps = new ArrayList<>();
        allDeps.addAll(manifest.dependencies.values());
        allDeps.addAll(manifest.devDependencies.values());
        Resolver.Resolution fresh = resolver.resolve(allDeps, existingLock);
        fresh.lock().save(lockPath, KwenzaLock.hashDependencies(manifest));
        if (verbose) {
            for (Resolver.ResolvedArtifact artifact : fresh.artifacts()) {
                boolean direct = artifact.requestedBy().contains("direct");
                String via = direct ? "direct" : "via " + String.join(", ", artifact.requestedBy());
                reporter.detail("  " + artifact.moduleId() + ":" + artifact.version() + "  (" + via + ")");
            }
        }
        reporter.info("resolve: " + fresh.artifacts().size() + " artifact(s) resolved -> " + lockPath.getFileName());
        Timing.mark("resolved (network)");
        return fresh;
    }

    /**
     * Resolves the dependencies-only subset, used for everything that ends up on a shipped
     * artifact's classpath (build/package/run) so dev-dependencies never leak into it.
     * Never touches the network on its own: {@code ensureResolved} above has already guaranteed
     * every POM/jar it needs is in the local cache, since dependencies is a subset of
     * dependencies + dev-dependencies.
     */
    public Resolver.Resolution ensureMainResolved() throws IOException {
        ensureResolved();
        if (mainResolution == null) {
            Resolver resolver = new Resolver(MavenRepository.central(localRepo));
            // The merged lock was verified moments ago, so its pins cover every jar this needs.
            mainResolution = resolver.resolve(manifest.dependencies.values(), resolution.lock());
        }
        return mainResolution;
    }

    /** Compiles at most once per invocation - later calls in the same command chain reuse the result. */
    public Resolver.Resolution ensureCompiled() throws Exception {
        Resolver.Resolution mainOnly = ensureMainResolved();
        if (compiled) {
            return mainOnly;
        }
        Path mainClasses = targetDir.resolve("classes");
        JavaCompilation.Result result = JavaCompilation.compile(
                projectRoot.resolve("src/main/java"), mainClasses, mainOnly.classpath(), manifest.javaVersion);
        Timing.mark("compiled");
        printDiagnostics(result.diagnostics);
        if (!result.success) {
            throw new CliException("Compilation failed");
        }
        copyResources(projectRoot.resolve("src/main/resources"), mainClasses);
        reporter.info("compile: " + projectRoot.relativize(mainClasses));
        compiled = true;
        return mainOnly;
    }

    /**
     * Compiles at most once per invocation - later calls in the same command chain reuse the
     * result. Needs both scopes on the classpath (main code and test code alike may reference
     * either), so it returns {@code ensureResolved}'s merged resolution, not
     * {@code ensureMainResolved}'s.
     */
    public Resolver.Resolution ensureTestCompiled() throws Exception {
        ensureCompiled();
        Resolver.Resolution merged = ensureResolved();
        if (testCompiled) {
            return merged;
        }
        Path mainClasses = targetDir.resolve("classes");
        Path testClasses = targetDir.resolve("test-classes");
        List<Path> testClasspath = new ArrayList<>();
        testClasspath.add(mainClasses);
        testClasspath.addAll(merged.classpath());
        JavaCompilation.Result result = JavaCompilation.compile(
                projectRoot.resolve("src/test/java"), testClasses, testClasspath, manifest.javaVersion);
        Timing.mark("test-compiled");
        printDiagnostics(result.diagnostics);
        if (!result.success) {
            throw new CliException("Test compilation failed");
        }
        Files.createDirectories(testClasses);
        copyResources(projectRoot.resolve("src/test/resources"), testClasses);
        reporter.info("compile: " + projectRoot.relativize(testClasses));
        testCompiled = true;
        return merged;
    }

    private static void copyResources(Path resourcesDir, Path outputDir) throws IOException {
        if (!Files.isDirectory(resourcesDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(resourcesDir)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                Path dest = outputDir.resolve(resourcesDir.relativize(file));
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void printDiagnostics(List<String> diagnostics) {
        for (String d : diagnostics) {
            System.err.println(d);
        }
    }
}
