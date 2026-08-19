package dev.kwenza.cli;

import dev.kwenza.compiler.JavaCompilation;
import dev.kwenza.manifest.Dependency;
import dev.kwenza.manifest.KwenzaLock;
import dev.kwenza.manifest.KwenzaManifest;
import dev.kwenza.packager.JarPackager;
import dev.kwenza.resolver.MavenRepository;
import dev.kwenza.resolver.Resolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class Main {

    public static void main(String[] args) {
        Timing.init(); // must be the very first statement - captures the true main() entry time
        try {
            run(args);
        } catch (CliException e) {
            Status.error("error: " + e.getMessage());
            Timing.report();
            System.exit(1);
        } catch (Exception e) {
            Status.error("error: " + (e.getMessage() != null ? e.getMessage() : e));
            Timing.report();
            System.exit(1);
        }
        Timing.report();
        // Force immediate exit rather than waiting on any lingering non-daemon
        // threads (e.g. the HTTP client's internal executor) to wind down.
        System.exit(0);
    }

    private static final Set<String> COMMANDS =
            Set.of("resolve", "build", "test", "package", "run", "clean");

    private static void run(String[] args) throws Exception {
        List<String> commands = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        for (String arg : args) {
            (arg.startsWith("--") ? flags : commands).add(arg);
        }
        if (commands.isEmpty()) {
            printUsage();
            System.exit(1);
            return;
        }
        for (String command : commands) {
            if (!COMMANDS.contains(command)) {
                Status.error("error: unknown command '" + command + "'");
                printUsage();
                System.exit(1);
                return;
            }
        }
        boolean update = flags.contains("--update");
        boolean verbose = flags.contains("--verbose");

        Path projectRoot = Path.of("").toAbsolutePath();
        Path manifestPath = projectRoot.resolve("kwenza.toml");
        if (!Files.exists(manifestPath)) {
            throw new CliException("No kwenza.toml found in " + projectRoot);
        }
        KwenzaManifest manifest = KwenzaManifest.load(manifestPath);
        Timing.mark("manifest-loaded");
        Path lockPath = projectRoot.resolve("kwenza.lock");
        Path targetDir = projectRoot.resolve(manifest.outputDir);
        String repoOverride = System.getenv("KWENZA_REPO");
        Path localRepo = repoOverride != null
                ? Path.of(repoOverride)
                : Path.of(System.getProperty("user.home"), ".m2", "repository");

        // Shared across every command in this invocation, so e.g. `kwenza build run` resolves
        // and compiles once, not once per command that happens to need it.
        BuildState state = new BuildState();

        for (String command : commands) {
            switch (command) {
                case "resolve" -> ensureResolved(state, manifest, lockPath, localRepo, update, verbose);
                case "build" -> ensureCompiled(state, manifest, projectRoot, lockPath, targetDir, localRepo, update, verbose);
                case "test" -> doTest(state, manifest, projectRoot, lockPath, targetDir, localRepo, update, verbose);
                case "package" -> doPackage(state, manifest, projectRoot, lockPath, targetDir, localRepo, update, verbose);
                case "run" -> doRun(state, manifest, projectRoot, lockPath, targetDir, localRepo, update, verbose);
                case "clean" -> doClean(state, targetDir);
            }
        }
    }

    /** Resolution and compilation results, memoized across every command in a single invocation. */
    private static final class BuildState {
        Resolver.Resolution resolution;      // dependencies + dev-dependencies merged, backed by kwenza.lock
        Resolver.Resolution mainResolution;   // dependencies only - derived locally, never hits the network on its own
        boolean compiled;
        boolean testCompiled;
    }

    private static Resolver.Resolution resolve(KwenzaManifest manifest, Path lockPath, Path localRepo,
                                                boolean update, boolean verbose) throws IOException {
        MavenRepository repo = MavenRepository.central(localRepo);
        Resolver resolver = new Resolver(repo);
        KwenzaLock existingLock = KwenzaLock.load(lockPath);

        if (!update && existingLock.isFreshFor(manifest)) {
            if (!existingLock.entries.isEmpty()) {
                Status.info("resolve: using locked dependencies (kwenza.lock)");
            }
            Resolver.Resolution resolution = resolver.ensureFromLock(existingLock);
            Timing.mark("resolved (from lock)");
            return resolution;
        }

        Status.info("resolve: fetching dependencies from Maven Central...");
        List<Dependency> allDeps = new ArrayList<>();
        allDeps.addAll(manifest.dependencies.values());
        allDeps.addAll(manifest.devDependencies.values());
        Resolver.Resolution resolution = resolver.resolve(allDeps);
        resolution.lock().save(lockPath, KwenzaLock.hashDependencies(manifest));
        if (verbose) {
            for (Resolver.ResolvedArtifact artifact : resolution.artifacts()) {
                boolean direct = artifact.requestedBy().contains("direct");
                String via = direct ? "direct" : "via " + String.join(", ", artifact.requestedBy());
                Status.detail("  " + artifact.moduleId() + ":" + artifact.version() + "  (" + via + ")");
            }
        }
        Status.info("resolve: " + resolution.artifacts().size() + " artifact(s) resolved -> " + lockPath.getFileName());
        Timing.mark("resolved (network)");
        return resolution;
    }

    /** Resolves at most once per invocation - later calls in the same command chain reuse the result. */
    private static Resolver.Resolution ensureResolved(BuildState state, KwenzaManifest manifest, Path lockPath,
                                                       Path localRepo, boolean update, boolean verbose) throws IOException {
        if (state.resolution == null) {
            state.resolution = resolve(manifest, lockPath, localRepo, update, verbose);
        }
        return state.resolution;
    }

    /**
     * Resolves the dependencies-only subset, used for everything that ends up on a shipped
     * artifact's classpath (build/package/run) so dev-dependencies never leak into it.
     * Never touches the network on its own: {@code ensureResolved} above has already guaranteed
     * every POM/jar it needs is in the local cache, since dependencies is a subset of
     * dependencies + dev-dependencies.
     */
    private static Resolver.Resolution ensureMainResolved(BuildState state, KwenzaManifest manifest, Path lockPath,
                                                          Path localRepo, boolean update, boolean verbose) throws IOException {
        ensureResolved(state, manifest, lockPath, localRepo, update, verbose);
        if (state.mainResolution == null) {
            Resolver resolver = new Resolver(MavenRepository.central(localRepo));
            state.mainResolution = resolver.resolve(manifest.dependencies.values());
        }
        return state.mainResolution;
    }

    /** Compiles at most once per invocation - later calls in the same command chain reuse the result. */
    private static Resolver.Resolution ensureCompiled(BuildState state, KwenzaManifest manifest, Path projectRoot,
                                                       Path lockPath, Path targetDir, Path localRepo, boolean update,
                                                       boolean verbose) throws Exception {
        Resolver.Resolution resolution = ensureMainResolved(state, manifest, lockPath, localRepo, update, verbose);
        if (state.compiled) {
            return resolution;
        }
        Path mainClasses = targetDir.resolve("classes");
        JavaCompilation.Result result = JavaCompilation.compile(
                projectRoot.resolve("src/main/java"), mainClasses, resolution.classpath(), manifest.javaVersion);
        Timing.mark("compiled");
        printDiagnostics(result.diagnostics);
        if (!result.success) {
            throw new CliException("Compilation failed");
        }
        copyResources(projectRoot.resolve("src/main/resources"), mainClasses);
        Status.info("compile: " + projectRoot.relativize(mainClasses));
        state.compiled = true;
        return resolution;
    }

    /**
     * Compiles at most once per invocation - later calls in the same command chain reuse the
     * result. Needs both scopes on the classpath (main code and test code alike may reference
     * either), so it uses {@code ensureResolved} (the merged resolution), not
     * {@code ensureMainResolved}.
     */
    private static Resolver.Resolution ensureTestCompiled(BuildState state, KwenzaManifest manifest, Path projectRoot,
                                                          Path lockPath, Path targetDir, Path localRepo, boolean update,
                                                          boolean verbose) throws Exception {
        ensureCompiled(state, manifest, projectRoot, lockPath, targetDir, localRepo, update, verbose);
        Resolver.Resolution resolution = ensureResolved(state, manifest, lockPath, localRepo, update, verbose);
        if (state.testCompiled) {
            return resolution;
        }
        Path mainClasses = targetDir.resolve("classes");
        Path testClasses = targetDir.resolve("test-classes");
        List<Path> testClasspath = new ArrayList<>();
        testClasspath.add(mainClasses);
        testClasspath.addAll(resolution.classpath());
        JavaCompilation.Result result = JavaCompilation.compile(
                projectRoot.resolve("src/test/java"), testClasses, testClasspath, manifest.javaVersion);
        Timing.mark("test-compiled");
        printDiagnostics(result.diagnostics);
        if (!result.success) {
            throw new CliException("Test compilation failed");
        }
        Files.createDirectories(testClasses);
        copyResources(projectRoot.resolve("src/test/resources"), testClasses);
        Status.info("compile: " + projectRoot.relativize(testClasses));
        state.testCompiled = true;
        return resolution;
    }

    private static void doPackage(BuildState state, KwenzaManifest manifest, Path projectRoot, Path lockPath,
                                   Path targetDir, Path localRepo, boolean update, boolean verbose) throws Exception {
        Resolver.Resolution resolution =
                ensureCompiled(state, manifest, projectRoot, lockPath, targetDir, localRepo, update, verbose);
        Path classes = targetDir.resolve("classes");
        Path outputJar = targetDir.resolve(manifest.name + "-" + manifest.version + ".jar");
        JarPackager.packageThinJar(classes, outputJar, manifest.mainClass, resolution.classpath());
        Timing.mark("packaged");
        Status.info("package: " + projectRoot.relativize(outputJar));
    }

    private static void doRun(BuildState state, KwenzaManifest manifest, Path projectRoot, Path lockPath,
                               Path targetDir, Path localRepo, boolean update, boolean verbose) throws Exception {
        if (manifest.mainClass == null) {
            throw new CliException("kwenza.toml has no [package] main-class set - required for `kwenza run`");
        }
        Resolver.Resolution resolution =
                ensureCompiled(state, manifest, projectRoot, lockPath, targetDir, localRepo, update, verbose);
        Path classes = targetDir.resolve("classes");

        List<String> classpathEntries = new ArrayList<>();
        classpathEntries.add(classes.toString());
        for (Path jar : resolution.classpath()) {
            classpathEntries.add(jar.toString());
        }
        String classpath = String.join(java.io.File.pathSeparator, classpathEntries);

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(classpath);
        command.add(manifest.mainClass);

        int depCount = resolution.classpath().size();
        String deps = depCount == 0 ? "" : " (+" + depCount + " dependency jar" + (depCount == 1 ? "" : "s") + ")";
        Status.info("run: java -cp " + projectRoot.relativize(classes) + deps + " " + manifest.mainClass);
        if (verbose) {
            Status.detail("  " + String.join(" ", command));
        }
        Process process = new ProcessBuilder(command).inheritIO().start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static void doTest(BuildState state, KwenzaManifest manifest, Path projectRoot, Path lockPath,
                                Path targetDir, Path localRepo, boolean update, boolean verbose) throws Exception {
        Resolver.Resolution resolution =
                ensureTestCompiled(state, manifest, projectRoot, lockPath, targetDir, localRepo, update, verbose);
        Path classes = targetDir.resolve("classes");
        Path testClasses = targetDir.resolve("test-classes");

        List<String> classpathEntries = new ArrayList<>();
        classpathEntries.add(classes.toString());
        classpathEntries.add(testClasses.toString());
        for (Path jar : resolution.classpath()) {
            classpathEntries.add(jar.toString());
        }
        String classpath = String.join(java.io.File.pathSeparator, classpathEntries);

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(classpath);
        command.add("org.junit.platform.console.ConsoleLauncher");
        command.add("execute");
        command.add("--scan-classpath=" + testClasses);

        int depCount = resolution.classpath().size();
        String deps = depCount == 0 ? "" : " (+" + depCount + " dependency jar" + (depCount == 1 ? "" : "s") + ")";
        Status.info("test: java -cp " + projectRoot.relativize(classes) + ":" + projectRoot.relativize(testClasses)
                + deps + " ConsoleLauncher");
        if (verbose) {
            Status.detail("  " + String.join(" ", command));
        }
        // A forked JVM, not an in-process call into the launcher API: keeps kwenza itself free of
        // any JUnit/Mockito classes (no compile-time dependency, nothing to reflect against), and
        // isolates kwenza's own process from a test calling System.exit() or Mockito's inline mock
        // maker self-attaching a Java agent - the same reason Surefire and Gradle's test workers fork.
        Process process = new ProcessBuilder(command).inheritIO().start();
        int exitCode = process.waitFor();
        if (exitCode == 0) {
            Status.info("test: passed");
        } else {
            Status.error("test: failed (exit " + exitCode + ")");
            System.exit(exitCode);
        }
    }

    private static void doClean(BuildState state, Path targetDir) throws IOException {
        // Whatever was compiled before this point in the chain is gone now - a later
        // build/package/run in the same chain must recompile, not reuse stale state.
        state.compiled = false;
        state.testCompiled = false;
        if (!Files.exists(targetDir)) {
            Status.info("clean: nothing to remove");
            return;
        }
        deleteRecursively(targetDir);
        Status.info("clean: removed " + targetDir.getFileName() + "/");
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

    private static void printUsage() {
        System.err.println("""
                Usage: kwenza <command> [<command> ...] [--update] [--verbose]

                Multiple commands run in sequence, e.g. `kwenza clean build run` or
                `kwenza build run`. Resolving and compiling only happens once per invocation,
                even if several chained commands would each otherwise trigger it.

                Commands:
                  build            resolve dependencies and compile
                  test             build, compile src/test/java, and run it via the JUnit
                                   Platform Console Launcher (needs a dev-dependency that
                                   provides org.junit.platform.console.ConsoleLauncher)
                  run              build and execute [package].main-class
                  package          build and produce a thin jar in the output directory
                  clean            remove the output directory
                  resolve          refresh kwenza.lock explicitly

                Flags:
                  --update         force a full re-resolution against Maven Central
                  --verbose        print extra detail: each dependency resolved (resolve/build/
                                   package/run/test), and the full run/test command
                                   including every classpath jar (run/test only)

                Environment:
                  KWENZA_REPO      override the local dependency cache (default: ~/.m2/repository)
                  KWENZA_TIMING    set to any value to print a phase-timing breakdown to stderr
                """);
    }

    private static final class CliException extends RuntimeException {
        CliException(String message) {
            super(message);
        }
    }
}
