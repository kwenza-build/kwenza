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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public final class Main {

    public static void main(String[] args) {
        Timing.init(); // must be the very first statement - captures the true main() entry time
        try {
            run(args);
        } catch (CliException e) {
            System.err.println("error: " + e.getMessage());
            Timing.report();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("error: " + (e.getMessage() != null ? e.getMessage() : e));
            Timing.report();
            System.exit(1);
        }
        Timing.report();
        // Force immediate exit rather than waiting on any lingering non-daemon
        // threads (e.g. the HTTP client's internal executor) to wind down.
        System.exit(0);
    }

    private static void run(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
            return;
        }
        String command = args[0];
        boolean update = Arrays.asList(args).subList(1, args.length).contains("--update");

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

        switch (command) {
            case "resolve" -> resolve(manifest, lockPath, localRepo, update);
            case "check" -> doCheck(manifest, projectRoot, lockPath, localRepo, update);
            case "build" -> resolveAndCompile(manifest, projectRoot, lockPath, targetDir, localRepo, update);
            case "package" -> doPackage(manifest, projectRoot, lockPath, targetDir, localRepo, update);
            case "run" -> doRun(manifest, projectRoot, lockPath, targetDir, localRepo, update);
            case "clean" -> doClean(targetDir);
            default -> {
                printUsage();
                System.exit(1);
            }
        }
    }

    private static Resolver.Resolution resolve(KwenzaManifest manifest, Path lockPath, Path localRepo, boolean update)
            throws IOException {
        MavenRepository repo = MavenRepository.central(localRepo);
        Resolver resolver = new Resolver(repo);
        KwenzaLock existingLock = KwenzaLock.load(lockPath);

        if (!update && existingLock.isFreshFor(manifest)) {
            if (!existingLock.entries.isEmpty()) {
                System.err.println("Using locked dependencies (kwenza.lock)");
            }
            Resolver.Resolution resolution = resolver.ensureFromLock(existingLock);
            Timing.mark("resolved (from lock)");
            return resolution;
        }

        System.err.println("Resolving dependencies from Maven Central...");
        List<Dependency> allDeps = new ArrayList<>();
        allDeps.addAll(manifest.dependencies.values());
        allDeps.addAll(manifest.devDependencies.values());
        Resolver.Resolution resolution = resolver.resolve(allDeps);
        resolution.lock().save(lockPath, KwenzaLock.hashDependencies(manifest));
        System.err.println("Resolved " + resolution.artifacts().size() + " artifact(s) -> " + lockPath.getFileName());
        Timing.mark("resolved (network)");
        return resolution;
    }

    /** Compile-only fast pass: does not touch the output directory, produces no artifact. */
    private static void doCheck(KwenzaManifest manifest, Path projectRoot, Path lockPath, Path localRepo, boolean update)
            throws Exception {
        Resolver.Resolution resolution = resolve(manifest, lockPath, localRepo, update);
        Path scratch = Files.createTempDirectory("kwenza-check-");
        try {
            JavaCompilation.Result result = JavaCompilation.compile(
                    projectRoot.resolve("src/main/java"), scratch, resolution.classpath(), manifest.javaVersion);
            printDiagnostics(result.diagnostics);
            if (!result.success) {
                throw new CliException("Compilation failed");
            }
            System.err.println("Check OK");
        } finally {
            deleteRecursively(scratch);
        }
    }

    private static Resolver.Resolution resolveAndCompile(KwenzaManifest manifest, Path projectRoot, Path lockPath,
                                                          Path targetDir, Path localRepo, boolean update) throws Exception {
        Resolver.Resolution resolution = resolve(manifest, lockPath, localRepo, update);
        List<Path> classpath = resolution.classpath();

        Path mainClasses = targetDir.resolve("classes");
        JavaCompilation.Result result = JavaCompilation.compile(
                projectRoot.resolve("src/main/java"), mainClasses, classpath, manifest.javaVersion);
        Timing.mark("compiled");
        printDiagnostics(result.diagnostics);
        if (!result.success) {
            throw new CliException("Compilation failed");
        }
        copyResources(projectRoot.resolve("src/main/resources"), mainClasses);
        System.err.println("Compiled to " + projectRoot.relativize(mainClasses));
        return resolution;
    }

    private static void doPackage(KwenzaManifest manifest, Path projectRoot, Path lockPath, Path targetDir,
                                   Path localRepo, boolean update) throws Exception {
        Resolver.Resolution resolution = resolveAndCompile(manifest, projectRoot, lockPath, targetDir, localRepo, update);
        Path classes = targetDir.resolve("classes");
        Path outputJar = targetDir.resolve(manifest.name + "-" + manifest.version + ".jar");
        JarPackager.packageThinJar(classes, outputJar, manifest.mainClass, resolution.classpath());
        Timing.mark("packaged");
        System.err.println("Packaged " + projectRoot.relativize(outputJar));
    }

    private static void doRun(KwenzaManifest manifest, Path projectRoot, Path lockPath, Path targetDir,
                               Path localRepo, boolean update) throws Exception {
        if (manifest.mainClass == null) {
            throw new CliException("kwenza.toml has no [package] main-class set - required for `kwenza run`");
        }
        Resolver.Resolution resolution = resolveAndCompile(manifest, projectRoot, lockPath, targetDir, localRepo, update);
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

        Process process = new ProcessBuilder(command).inheritIO().start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static void doClean(Path targetDir) throws IOException {
        if (!Files.exists(targetDir)) {
            System.err.println("Nothing to clean");
            return;
        }
        deleteRecursively(targetDir);
        System.err.println("Removed " + targetDir.getFileName() + "/");
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
                Usage: kwenza <command> [--update]

                Commands:
                  build            resolve dependencies and compile
                  check            resolve and compile-only, no artifact
                  run              build and execute [package].main-class
                  package          build and produce a thin jar in the output directory
                  clean            remove the output directory
                  resolve          refresh kwenza.lock explicitly

                Flags:
                  --update         force a full re-resolution against Maven Central

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
