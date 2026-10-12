import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Performance regression check: runs two kwenza jars (a base and a head) against the same real
 * project, interleaved on the same machine, and fails if head is meaningfully slower. Single-file
 * program (JEP 330), run from the benchmarks/ directory. See benchmarks/README.adoc.
 *
 * Usage: java Regress.java <base-jar> <head-jar> [runs] [threshold-percent]
 *   runs defaults to 10 per jar per operation; threshold defaults to 15 (percent).
 *
 * Comparing two jars in one job, rather than a stored baseline against a new run, is what makes
 * this usable on shared CI runners: absolute times vary a lot between machines and between days,
 * but two jars measured alternately seconds apart on the same machine see the same noise.
 */
public final class Regress {

    /** The real project, pinned (Bench.java's commons-lang3 scenario fetches the same checkout). Its sources are
     * fetched at this commit; the manifest and lock live in this repo. */
    private static final String PROJECT = "commons-lang3";
    private static final String PROJECT_URL = "https://github.com/apache/commons-lang.git";
    private static final String PROJECT_TAG = "rel/commons-lang-3.19.0";
    private static final String PROJECT_COMMIT = "3d1eb7718a79d18cc2ab57e7ab3b894426ec7c2f";

    /** Below this absolute slowdown a result is never a failure, however large in percent. */
    /** Downloads are slow and the network is the noisiest thing measured, so cap how many of them we make. */
    private static final int DOWNLOAD_RUNS = 5;

    private static final double MIN_DELTA_SECONDS = 0.05;

    private static final Path HERE = Path.of("").toAbsolutePath();
    private static final Path LOG = Path.of(System.getProperty("java.io.tmpdir"), "kwenza-regress-out.log");

    /** What gets timed. Each operation is set up to do real work on every run. */
    private enum Operation {
        /** No dependencies to fetch: warm resolve from the lock, javac over ~100k lines, jar. javac dominates. */
        PACKAGE("package (no deps)", List.of("package"), false),
        /** Warm-cache resolution of the dev-dependency graph with no lock: POM parsing and mediation. */
        RESOLVE("resolve (warm cache)", List.of("resolve"), false),
        /** The same resolution from an empty cache: real downloads and checksum checks. Network-bound, so noisier. */
        DOWNLOAD("resolve (download)", List.of("resolve"), true);

        final String label;
        final List<String> args;
        final boolean emptyCache;

        Operation(String label, List<String> args, boolean emptyCache) {
            this.label = label;
            this.args = args;
            this.emptyCache = emptyCache;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            fail("usage: java Regress.java <base-jar> <head-jar> [runs] [threshold-percent]");
        }
        if (!Files.isDirectory(HERE.resolve("commons-lang3"))) {
            fail("run this from the benchmarks/ directory (java Regress.java ...)");
        }
        Path baseJar = Path.of(args[0]).toAbsolutePath();
        Path headJar = Path.of(args[1]).toAbsolutePath();
        int runs = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        double threshold = (args.length > 3 ? Double.parseDouble(args[3]) : 15) / 100.0;
        for (Path jar : List.of(baseJar, headJar)) {
            if (!Files.isRegularFile(jar)) fail("no such jar: " + jar);
        }

        Path cache = HERE.resolve(PROJECT).resolve(".cache");
        Path repo = cache.resolve("m2");
        Path workDir = prepareProject(cache);
        Map<String, String> env = Map.of("KWENZA_REPO", repo.toString());

        // Fill the isolated cache (POMs and jars), untimed, so no run measures the network.
        System.out.println("-- priming the dependency cache --");
        Files.deleteIfExists(workDir.resolve("kwenza.lock"));
        timed(workDir, baseJar, List.of("resolve"), env);
        Path lock = workDir.resolve("kwenza.lock");
        Path pristineLock = cache.resolve("kwenza.lock.pristine");
        Files.copy(lock, pristineLock, StandardCopyOption.REPLACE_EXISTING);

        Map<Operation, Map<String, List<Double>>> times = new LinkedHashMap<>();
        for (Operation op : Operation.values()) {
            System.out.println("-- " + op.label + " x " + (op.emptyCache ? Math.min(runs, DOWNLOAD_RUNS) : runs) + " per jar, interleaved --");
            Map<String, List<Double>> byJar = new LinkedHashMap<>();
            byJar.put("base", new ArrayList<>());
            byJar.put("head", new ArrayList<>());
            // One untimed pass per jar so neither pays for a cold OS file cache.
            for (Path jar : List.of(baseJar, headJar)) {
                reset(workDir, pristineLock, op, repo);
                timed(workDir, jar, op.args, env);
            }
            int opRuns = op.emptyCache ? Math.min(runs, DOWNLOAD_RUNS) : runs;
            for (int i = 1; i <= opRuns; i++) {
                // Alternate who goes first, so a drifting machine doesn't favour one side.
                List<String> order = i % 2 == 1 ? List.of("base", "head") : List.of("head", "base");
                for (String who : order) {
                    reset(workDir, pristineLock, op, repo);
                    double e = timed(workDir, who.equals("base") ? baseJar : headJar, op.args, env);
                    byJar.get(who).add(e);
                }
                System.out.printf("  run %2d: base %.3fs  head %.3fs%n",
                        i, last(byJar.get("base")), last(byJar.get("head")));
            }
            times.put(op, byJar);
        }

        boolean regressed = report(times, threshold);
        System.exit(regressed ? 1 : 0);
    }

    /** Clones the pinned project once, checks it is the commit we expect, and drops our manifest and lock in. */
    private static Path prepareProject(Path cache) throws IOException, InterruptedException {
        Path src = cache.resolve("src");
        if (!Files.isDirectory(src.resolve(".git"))) {
            Files.createDirectories(cache);
            System.out.println("-- fetching " + PROJECT + " " + PROJECT_TAG + " --");
            run(cache, List.of("git", "clone", "--quiet", "--depth", "1", "--branch", PROJECT_TAG, PROJECT_URL, "src"));
        }
        String head = output(src, List.of("git", "rev-parse", "HEAD")).strip();
        if (!head.equals(PROJECT_COMMIT)) {
            fail(PROJECT_TAG + " is " + head + ", expected " + PROJECT_COMMIT
                    + " - the tag moved, or " + src + " is stale; delete it and re-run");
        }
        // kwenza-deps, not kwenza: Bench.java's variant has no dev-dependencies, to match Maven and Gradle.
        Path manifests = HERE.resolve(PROJECT).resolve("kwenza-deps");
        Files.copy(manifests.resolve("kwenza.toml"), src.resolve("kwenza.toml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(manifests.resolve("kwenza.lock"), src.resolve("kwenza.lock"), StandardCopyOption.REPLACE_EXISTING);
        return src;
    }

    /** Puts the project in the state an operation needs to do all of its work. */
    private static void reset(Path workDir, Path pristineLock, Operation op, Path repo) throws IOException {
        deleteRecursively(workDir.resolve("target"));
        if (op.emptyCache) {
            deleteRecursively(repo);
            Files.createDirectories(repo);
        }
        if (op != Operation.PACKAGE) {
            Files.deleteIfExists(workDir.resolve("kwenza.lock"));
        } else {
            Files.copy(pristineLock, workDir.resolve("kwenza.lock"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Prints the table, writes it to the GitHub job summary if there is one, returns whether anything regressed. */
    private static boolean report(Map<Operation, Map<String, List<Double>>> times, double threshold) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("### kwenza performance: base vs head on ").append(PROJECT).append("\n\n");
        md.append("| operation | base median | head median | change | base min | head min | verdict |\n");
        md.append("|---|---:|---:|---:|---:|---:|---|\n");
        boolean regressed = false;
        for (var entry : times.entrySet()) {
            List<Double> base = entry.getValue().get("base");
            List<Double> head = entry.getValue().get("head");
            double baseMedian = median(base);
            double headMedian = median(head);
            double delta = headMedian - baseMedian;
            double minDelta = min(head) - min(base);
            // Both must be over: the median alone flags a busy machine, the min alone flags one lucky run.
            boolean bad = delta > MIN_DELTA_SECONDS && delta / baseMedian > threshold
                    && minDelta > MIN_DELTA_SECONDS && minDelta / min(base) > threshold;
            regressed |= bad;
            md.append(String.format("| %s | %.3fs | %.3fs | %+.1f%% | %.3fs | %.3fs | %s |%n",
                    entry.getKey().label, baseMedian, headMedian, 100 * delta / baseMedian,
                    min(base), min(head), bad ? "REGRESSION" : "ok"));
        }
        md.append(String.format("%nFails when head's median and its min are both more than %.0f%% (and %.0f ms) slower than base's. "
                + "Interleaved runs on one machine; the min guards against a busy runner inflating the median.%n", threshold * 100, MIN_DELTA_SECONDS * 1000));
        System.out.println();
        System.out.print(md);
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null && !summary.isBlank()) {
            Files.writeString(Path.of(summary), md.toString(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
        return regressed;
    }

    private static double timed(Path workDir, Path jar, List<String> args, Map<String, String> env)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("java", "-jar", jar.toString()));
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectOutput(LOG.toFile())
                .redirectError(LOG.toFile());
        pb.environment().putAll(env);
        long start = System.nanoTime();
        int exitCode = pb.start().waitFor();
        double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
        if (exitCode != 0) {
            System.err.printf("FAILED (exit %d, %.2fs): %s%n", exitCode, elapsed, command);
            for (String line : Files.readAllLines(LOG)) System.err.println(line);
            System.exit(1);
        }
        return elapsed;
    }

    private static void run(Path dir, List<String> command) throws IOException, InterruptedException {
        int code = new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start().waitFor();
        if (code != 0) fail(command + " exited " + code);
    }

    private static String output(Path dir, List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) fail(command + " failed: " + out);
        return out;
    }

    private static double last(List<Double> xs) {
        return xs.get(xs.size() - 1);
    }

    private static double min(List<Double> xs) {
        return xs.stream().min(Double::compareTo).orElseThrow();
    }

    private static double median(List<Double> xs) {
        List<Double> sorted = xs.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static void fail(String message) {
        System.err.println("error: " + message);
        System.exit(2);
    }
}
