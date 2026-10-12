import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Performance regression check: runs kwenza jars against the same real project, interleaved on the
 * same machine, and fails if head is meaningfully slower. Single-file program (JEP 330), run from
 * the benchmarks/ directory. See benchmarks/README.adoc.
 *
 * Usage: java Regress.java <base-jar> <head-jar> [runs] [--reference <jar>]
 *   runs defaults to 10 per jar per operation.
 *
 * Two comparisons, because they catch different things:
 *  - head vs base (the PR's target branch): has this change made things slower? Each operation has
 *    its own threshold, tight enough to catch a real step.
 *  - head vs reference (a pinned commit, benchmarks/REFERENCE): has kwenza got slower overall? Each
 *    PR is only compared with its parent, so many small regressions would each pass the first check;
 *    this one has a cumulative budget that they add up against.
 *
 * Comparing jars in one job, rather than a stored baseline against a new run, is what makes this
 * usable on shared CI runners: absolute times vary a lot between machines and between days, but jars
 * measured alternately seconds apart on the same machine see the same noise.
 */
public final class Regress {

    /**
     * The real project, pinned (Bench.java's commons-lang3 scenario fetches the same checkout). Its
     * sources are fetched at this commit; the manifest and lock live in this repo.
     */
    private static final String PROJECT = "commons-lang3";
    private static final String PROJECT_URL = "https://github.com/apache/commons-lang.git";
    private static final String PROJECT_TAG = "rel/commons-lang-3.19.0";
    private static final String PROJECT_COMMIT = "3d1eb7718a79d18cc2ab57e7ab3b894426ec7c2f";

    /** Downloads are slow and the network is the noisiest thing measured, so cap how many of them we make. */
    private static final int DOWNLOAD_RUNS = 5;

    /** Below this absolute slowdown a result is never a failure, however large in percent. */
    private static final double MIN_DELTA_SECONDS = 0.05;

    private static final Path HERE = Path.of("").toAbsolutePath();
    private static final Path LOG = Path.of(System.getProperty("java.io.tmpdir"), "kwenza-regress-out.log");

    /**
     * What gets timed. Each operation is set up to do real work on every run.
     *
     * threshold is how much slower than the base a PR may make it, budget how much slower than the
     * pinned reference kwenza as a whole may get. Both are provisional. On a laptop identical jars
     * differ by about 1%, but on the first CI run (ubuntu-latest) identical code differed by up to 9%
     * on the fastest of ten runs, so these sit above that (see benchmarks/README.adoc) and should be
     * tightened once there is more CI data. Network-bound work gets much more room.
     */
    private enum Operation {
        /** No dependencies to fetch: warm resolve from the lock, javac over ~100k lines, jar. javac dominates. */
        PACKAGE("package (no deps)", List.of("package"), false, 0.12, 0.15),
        /** Warm-cache resolution of the dev-dependency graph with no lock: POM parsing and mediation. */
        RESOLVE("resolve (warm cache)", List.of("resolve"), false, 0.12, 0.15),
        /** The same resolution from an empty cache: real downloads and checksum checks. Network-bound, so noisier. */
        DOWNLOAD("resolve (download)", List.of("resolve"), true, 0.30, 0.40);

        final String label;
        final List<String> args;
        final boolean emptyCache;
        final double threshold;
        final double budget;

        Operation(String label, List<String> args, boolean emptyCache, double threshold, double budget) {
            this.label = label;
            this.args = args;
            this.emptyCache = emptyCache;
            this.threshold = threshold;
            this.budget = budget;
        }

        int runs(int requested) {
            return emptyCache ? Math.min(requested, DOWNLOAD_RUNS) : requested;
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> positional = new ArrayList<>();
        Path referenceJar = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--reference")) {
                if (++i == args.length) fail("--reference needs a jar");
                referenceJar = Path.of(args[i]).toAbsolutePath();
            } else {
                positional.add(args[i]);
            }
        }
        if (positional.size() < 2) {
            fail("usage: java Regress.java <base-jar> <head-jar> [runs] [--reference <jar>]");
        }
        if (!Files.isDirectory(HERE.resolve("commons-lang3"))) {
            fail("run this from the benchmarks/ directory (java Regress.java ...)");
        }
        int runs = positional.size() > 2 ? Integer.parseInt(positional.get(2)) : 10;

        // Order matters: it's the order columns appear in, and base is used to prime the cache.
        Map<String, Path> jars = new LinkedHashMap<>();
        jars.put("base", Path.of(positional.get(0)).toAbsolutePath());
        jars.put("head", Path.of(positional.get(1)).toAbsolutePath());
        if (referenceJar != null) jars.put("reference", referenceJar);
        for (Path jar : jars.values()) {
            if (!Files.isRegularFile(jar)) fail("no such jar: " + jar);
        }

        Path cache = HERE.resolve(PROJECT).resolve(".cache");
        Path repo = cache.resolve("m2");
        Path workDir = prepareProject(cache);
        Map<String, String> env = Map.of("KWENZA_REPO", repo.toString());

        // Fill the isolated cache (POMs and jars), untimed, so no run measures the network.
        System.out.println("-- priming the dependency cache --");
        Files.deleteIfExists(workDir.resolve("kwenza.lock"));
        timed(workDir, jars.get("base"), List.of("resolve"), env);
        Path pristineLock = cache.resolve("kwenza.lock.pristine");
        Files.copy(workDir.resolve("kwenza.lock"), pristineLock, StandardCopyOption.REPLACE_EXISTING);

        Map<Operation, Map<String, List<Double>>> times = new LinkedHashMap<>();
        for (Operation op : Operation.values()) {
            int opRuns = op.runs(runs);
            System.out.println("-- " + op.label + " x " + opRuns + " per jar, interleaved --");
            Map<String, List<Double>> byJar = new LinkedHashMap<>();
            for (String who : jars.keySet()) byJar.put(who, new ArrayList<>());
            // One untimed pass per jar so none pays for a cold OS file cache.
            for (Path jar : jars.values()) {
                reset(workDir, pristineLock, op, repo);
                timed(workDir, jar, op.args, env);
            }
            for (int i = 0; i < opRuns; i++) {
                // Rotate who goes first, so a drifting machine doesn't favour any one jar.
                List<String> order = new ArrayList<>(jars.keySet());
                Collections.rotate(order, i);
                StringBuilder line = new StringBuilder(String.format("  run %2d:", i + 1));
                for (String who : order) {
                    reset(workDir, pristineLock, op, repo);
                    double e = timed(workDir, jars.get(who), op.args, env);
                    byJar.get(who).add(e);
                    line.append(String.format("  %s %.3fs", who, e));
                }
                System.out.println(line);
            }
            times.put(op, byJar);
        }

        System.exit(report(times) ? 1 : 0);
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

    /** Prints the table, writes it to the GitHub job summary if there is one, returns whether anything failed. */
    private static boolean report(Map<Operation, Map<String, List<Double>>> times) throws IOException {
        boolean hasReference = times.values().iterator().next().containsKey("reference");
        StringBuilder md = new StringBuilder();
        md.append("### kwenza performance on ").append(PROJECT).append("\n\n");
        md.append("| operation | base | head |").append(hasReference ? " reference |" : "")
                .append(" head vs base | limit |").append(hasReference ? " head vs reference | budget |" : "")
                .append(" verdict |\n");
        md.append("|---|---:|---:|").append(hasReference ? "---:|" : "").append("---:|---:|")
                .append(hasReference ? "---:|---:|" : "").append("---|\n");
        boolean failed = false;
        for (var entry : times.entrySet()) {
            Operation op = entry.getKey();
            List<Double> base = entry.getValue().get("base");
            List<Double> head = entry.getValue().get("head");
            boolean regressed = slower(head, base, op.threshold);
            md.append(String.format("| %s | %.3fs | %.3fs |", op.label, median(base), median(head)));
            if (hasReference) md.append(String.format(" %.3fs |", median(entry.getValue().get("reference"))));
            md.append(String.format(" %s | %.0f%% |", change(head, base), op.threshold * 100));
            boolean overBudget = false;
            if (hasReference) {
                List<Double> reference = entry.getValue().get("reference");
                overBudget = slower(head, reference, op.budget);
                md.append(String.format(" %s | %.0f%% |", change(head, reference), op.budget * 100));
            }
            md.append(regressed ? " REGRESSION |" : overBudget ? " OVER BUDGET |" : " ok |").append('\n');
            failed |= regressed || overBudget;
        }
        md.append(String.format("%nEach cell is median / fastest. A limit is exceeded only when head's median and its fastest "
                + "run are both over it, and by more than %.0f ms; the median alone flags a busy machine, the fastest "
                + "alone a lucky run. Interleaved runs on one machine.%n", MIN_DELTA_SECONDS * 1000));
        if (hasReference) {
            md.append("\nThe reference is the commit pinned in `benchmarks/REFERENCE`; the budget is what kwenza as a "
                    + "whole may lose against it, however many PRs it takes.\n");
        }
        System.out.println();
        System.out.print(md);
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null && !summary.isBlank()) {
            Files.writeString(Path.of(summary), md.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return failed;
    }

    /** Whether head is slower than other by more than the limit, on both the median and the fastest run. */
    private static boolean slower(List<Double> head, List<Double> other, double limit) {
        return exceeds(median(head), median(other), limit) && exceeds(min(head), min(other), limit);
    }

    private static boolean exceeds(double head, double other, double limit) {
        return head - other > MIN_DELTA_SECONDS && (head - other) / other > limit;
    }

    private static String change(List<Double> head, List<Double> other) {
        return String.format("%+.1f%% / %+.1f%%", 100 * (median(head) / median(other) - 1),
                100 * (min(head) / min(other) - 1));
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
