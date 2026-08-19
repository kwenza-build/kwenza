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

    private static final Set<String> COMMANDS = Set.of("resolve", "build", "package", "run", "clean");

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
                case "package" -> doPackage(state, manifest, projectRoot, lockPath, targetDir, localRepo, update, verbose);
                case "run" -> doRun(state, manifest, projectRoot, lockPath, targetDir, localRepo, update, verbose);
                case "clean" -> doClean(state, targetDir);
            }
        }
    }

    /** Resolution and compilation results, memoized across every command in a single invocation. */
    private static final class BuildState {
        Resolver.Resolution resolution;
        boolean compiled;
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

    /** Compiles at most once per invocation - later calls in the same command chain reuse the result. */
    private static Resolver.Resolution ensureCompiled(BuildState state, KwenzaManifest manifest, Path projectRoot,
                                                       Path lockPath, Path targetDir, Path localRepo, boolean update,
                                                       boolean verbose) throws Exception {
        Resolver.Resolution resolution = ensureResolved(state, manifest, lockPath, localRepo, update, verbose);
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

    private static void doClean(BuildState state, Path targetDir) throws IOException {
        // Whatever was compiled before this point in the chain is gone now - a later
        // build/package/run in the same chain must recompile, not reuse stale state.
        state.compiled = false;
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
                  run              build and execute [package].main-class
                  package          build and produce a thin jar in the output directory
                  clean            remove the output directory
                  resolve          refresh kwenza.lock explicitly

                Flags:
                  --update         force a full re-resolution against Maven Central
                  --verbose        print extra detail: each dependency resolved (resolve/build/
                                   package/run), and the full run command including every
                                   classpath jar (run only)

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
