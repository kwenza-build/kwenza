import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Timed comparison of kwenza vs Maven vs Gradle, across every benchmark scenario under this
 * directory. Single-file program (JEP 330) - no build step, no dependency beyond the JDK, run
 * directly with the working directory set to benchmarks/. See benchmarks/README.adoc.
 *
 * Usage: java Bench.java [<scenario>|all] [cold|warm|both] [warm-runs]
 *   scenario defaults to "all"; mode defaults to "both"; warm-runs defaults to 5.
 *
 * "package" always means compile+package only (Maven's -Dmaven.test.skip, Gradle's `assemble`
 * rather than `build`) so it measures the same thing for every tool - Maven's `package` and
 * Gradle's `build` otherwise run the test phase first, which would make "package" silently
 * include test execution for those two tools but not for kwenza. `test` is its own separate,
 * explicitly-timed operation for exactly that reason.
 */
public final class Bench {

    record ScenarioSpec(String name, List<String> operations) {}

    private static final List<ScenarioSpec> SCENARIOS = List.of(
            new ScenarioSpec("hello-world", List.of("package")),
            new ScenarioSpec("word-counter", List.of("package", "test"))
    );
    private static final List<String> TOOLS = List.of("kwenza", "maven", "gradle");

    private static final Path HERE = Path.of("").toAbsolutePath();
    private static final Path LOG = Path.of(System.getProperty("java.io.tmpdir"), "kwenza-bench-out.log");

    record Result(String scenario, String operation, String tool, Double cold, Double warmMin, Double warmMedian) {}

    public static void main(String[] args) throws Exception {
        if (!Files.isDirectory(HERE.resolve("hello-world"))) {
            fail("run this from the benchmarks/ directory (java Bench.java ...)");
        }
        String scenarioArg = args.length > 0 ? args[0] : "all";
        String mode = args.length > 1 ? args[1] : "both";
        int warmRuns = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        if (!List.of("cold", "warm", "both").contains(mode)) {
            fail("unknown mode '" + mode + "' (expected cold, warm, or both)");
        }
        List<ScenarioSpec> scenarios = scenarioArg.equals("all")
                ? SCENARIOS
                : SCENARIOS.stream().filter(s -> s.name().equals(scenarioArg)).toList();
        if (scenarios.isEmpty()) {
            fail("unknown scenario '" + scenarioArg + "' (expected one of "
                    + SCENARIOS.stream().map(ScenarioSpec::name).toList() + ", or 'all')");
        }

        List<Result> results = new ArrayList<>();
        for (ScenarioSpec scenario : scenarios) {
            Path cacheDir = HERE.resolve(scenario.name()).resolve(".cache");
            for (String operation : scenario.operations()) {
                System.out.println("=== " + scenario.name() + " / " + operation + " ===");
                Map<String, Double> cold = new LinkedHashMap<>();
                Map<String, List<Double>> warm = new LinkedHashMap<>();

                if (mode.equals("cold") || mode.equals("both")) {
                    System.out.println("-- cold (wiping isolated caches, real network fetch) --");
                    for (String tool : TOOLS) {
                        Path toolDir = HERE.resolve(scenario.name()).resolve(tool);
                        wipeCaches(cacheDir);
                        resetOutputsAndLock(toolDir, tool);
                        double e = timed(toolDir, command(tool, operation, cacheDir), env(tool, cacheDir));
                        System.out.printf("  %-8s %.2fs%n", tool, e);
                        cold.put(tool, e);
                    }
                }

                if (mode.equals("warm") || mode.equals("both")) {
                    System.out.println("-- warm (dependency caches populated, clean rebuild x " + warmRuns + ") --");
                    for (String tool : TOOLS) {
                        Path toolDir = HERE.resolve(scenario.name()).resolve(tool);
                        ensureCacheDirs(cacheDir);
                        timed(toolDir, command(tool, operation, cacheDir), env(tool, cacheDir)); // prime, untimed
                        List<Double> times = new ArrayList<>();
                        for (int i = 1; i <= warmRuns; i++) {
                            resetOutputsOnly(toolDir);
                            double e = timed(toolDir, command(tool, operation, cacheDir), env(tool, cacheDir));
                            times.add(e);
                            System.out.printf("  %-8s run %d: %.2fs%n", tool, i, e);
                        }
                        warm.put(tool, times);
                        if (tool.equals("gradle")) stopGradleDaemon(toolDir, cacheDir);
                    }
                }

                for (String tool : TOOLS) {
                    results.add(new Result(scenario.name(), operation, tool,
                            cold.get(tool),
                            warm.containsKey(tool) ? min(warm.get(tool)) : null,
                            warm.containsKey(tool) ? median(warm.get(tool)) : null));
                }
            }
        }

        printSummary(results);
    }

    private static List<String> command(String tool, String operation, Path cacheDir) {
        Path mavenRepo = cacheDir.resolve("maven-m2");
        return switch (tool) {
            case "kwenza" -> List.of("java", "-jar", kwenzaJar().toString(), operation);
            case "maven" -> operation.equals("package")
                    ? List.of("./mvnw", "-q", "-Dmaven.repo.local=" + mavenRepo, "-Dmaven.test.skip=true", "package")
                    : List.of("./mvnw", "-q", "-Dmaven.repo.local=" + mavenRepo, "test");
            case "gradle" -> List.of("./gradlew", operation.equals("package") ? "assemble" : "test");
            default -> throw new IllegalArgumentException(tool);
        };
    }

    private static Map<String, String> env(String tool, Path cacheDir) {
        return switch (tool) {
            case "kwenza" -> Map.of("KWENZA_REPO", cacheDir.resolve("kwenza-m2").toString());
            case "gradle" -> Map.of("GRADLE_USER_HOME", cacheDir.resolve("gradle-home").toString());
            default -> Map.of();
        };
    }

    private static Path kwenzaJar() {
        Path targetDir = HERE.getParent().resolve("target");
        try (Stream<Path> files = Files.list(targetDir)) {
            return files.filter(p -> p.getFileName().toString().matches("kwenza-\\d.*\\.jar"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElseThrow(() -> new IllegalStateException(
                            "no kwenza jar found in " + targetDir + " - build kwenza first (bin/kwenza package)"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Preserves Gradle's downloaded wrapper *distribution* - re-fetching a ~120MB tool binary isn't part of what's measured. */
    private static void wipeCaches(Path cacheDir) throws IOException {
        deleteContents(cacheDir.resolve("kwenza-m2"));
        deleteContents(cacheDir.resolve("maven-m2"));
        deleteRecursively(cacheDir.resolve("gradle-home").resolve("caches"));
        deleteRecursively(cacheDir.resolve("gradle-home").resolve("daemon"));
        ensureCacheDirs(cacheDir);
    }

    private static void ensureCacheDirs(Path cacheDir) throws IOException {
        Files.createDirectories(cacheDir.resolve("kwenza-m2"));
        Files.createDirectories(cacheDir.resolve("maven-m2"));
        Files.createDirectories(cacheDir.resolve("gradle-home"));
    }

    private static void resetOutputsOnly(Path toolDir) throws IOException {
        deleteRecursively(toolDir.resolve("target"));
        deleteRecursively(toolDir.resolve("build"));
    }

    /** Also drops kwenza.lock, forcing a genuinely fresh resolution - only ever used before a cold run. */
    private static void resetOutputsAndLock(Path toolDir, String tool) throws IOException {
        resetOutputsOnly(toolDir);
        if (tool.equals("kwenza")) {
            Files.deleteIfExists(toolDir.resolve("kwenza.lock"));
        }
    }

    private static double timed(Path workDir, List<String> command, Map<String, String> env)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectOutput(LOG.toFile())
                .redirectError(LOG.toFile());
        pb.environment().putAll(env);
        long start = System.nanoTime();
        Process process = pb.start();
        int exitCode = process.waitFor();
        double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
        if (exitCode != 0) {
            System.err.printf("FAILED (%.2fs) in %s - see %s%n", elapsed, workDir, LOG);
            for (String line : Files.readAllLines(LOG)) {
                System.err.println(line);
            }
            System.exit(1);
        }
        return elapsed;
    }

    private static void stopGradleDaemon(Path toolDir, Path cacheDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("./gradlew", "--stop")
                    .directory(toolDir.toFile())
                    .redirectOutput(LOG.toFile())
                    .redirectError(LOG.toFile());
            pb.environment().put("GRADLE_USER_HOME", cacheDir.resolve("gradle-home").toString());
            pb.start().waitFor();
        } catch (IOException | InterruptedException ignored) {
            // best-effort cleanup only
        }
    }

    private static double min(List<Double> xs) {
        return xs.stream().min(Double::compareTo).orElseThrow();
    }

    private static double median(List<Double> xs) {
        List<Double> sorted = xs.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static void printSummary(List<Result> results) {
        System.out.println();
        System.out.println("=== SUMMARY (warm: min / median over the configured run count) ===");
        System.out.printf("%-14s %-9s %-8s %10s %10s %10s%n",
                "scenario", "operation", "tool", "cold(s)", "min(s)", "median(s)");
        for (Result r : results) {
            System.out.printf("%-14s %-9s %-8s %10s %10s %10s%n",
                    r.scenario(), r.operation(), r.tool(), fmt(r.cold()), fmt(r.warmMin()), fmt(r.warmMedian()));
        }
    }

    private static String fmt(Double d) {
        return d == null ? "-" : String.format("%.2f", d);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void deleteContents(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path p : entries.toList()) {
                deleteRecursively(p);
            }
        }
    }

    private static void fail(String message) {
        System.err.println("error: " + message);
        System.exit(1);
    }
}
