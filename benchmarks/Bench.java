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
            new ScenarioSpec("junit-word-count", List.of("package", "test")),
            new ScenarioSpec("commons-lang3", List.of("package"))
    );
    private static final List<String> TOOLS = List.of("kwenza", "maven", "gradle", "gradle-nodaemon");

    /** "gradle-nodaemon" is the same project as "gradle", run with --no-daemon; directories are per build tool. */
    private static String dirName(String tool) {
        return tool.startsWith("gradle") ? "gradle" : tool;
    }

    private static final String LANG3_URL = "https://github.com/apache/commons-lang.git";
    private static final String LANG3_TAG = "rel/commons-lang-3.19.0";
    private static final String LANG3_COMMIT = "3d1eb7718a79d18cc2ab57e7ab3b894426ec7c2f";

    private static final Path HERE = Path.of("").toAbsolutePath();
    private static final Path LOG = Path.of(System.getProperty("java.io.tmpdir"), "kwenza-bench-out.log");

    record Result(String scenario, String operation, String tool, Double cold, Double warmMin, Double warmMedian) {}

    public static void main(String[] rawArgs) throws Exception {
        boolean writeDocs = List.of(rawArgs).contains("--write-docs");
        String[] args = List.of(rawArgs).stream().filter(a -> !a.equals("--write-docs")).toArray(String[]::new);
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
            if (scenario.name().equals("commons-lang3")) fetchCommonsLang3();
            Path cacheDir = HERE.resolve(scenario.name()).resolve(".cache");
            for (String operation : scenario.operations()) {
                System.out.println("=== " + scenario.name() + " / " + operation + " ===");
                Map<String, Double> cold = new LinkedHashMap<>();
                Map<String, List<Double>> warm = new LinkedHashMap<>();

                if (mode.equals("cold") || mode.equals("both")) {
                    System.out.println("-- cold (wiping isolated caches, real network fetch) --");
                    for (String tool : TOOLS) {
                        Path toolDir = HERE.resolve(scenario.name()).resolve(dirName(tool));
                        wipeCaches(cacheDir);
                        resetOutputsAndLock(toolDir, tool);
                        double e = timed(toolDir, command(tool, operation, cacheDir), env(tool, cacheDir));
                        System.out.printf("  %-16s %.2fs%n", tool, e);
                        cold.put(tool, e);
                    }
                }

                if (mode.equals("warm") || mode.equals("both")) {
                    System.out.println("-- warm (dependency caches populated, clean rebuild x " + warmRuns + ") --");
                    for (String tool : TOOLS) {
                        Path toolDir = HERE.resolve(scenario.name()).resolve(dirName(tool));
                        ensureCacheDirs(cacheDir);
                        timed(toolDir, command(tool, operation, cacheDir), env(tool, cacheDir)); // prime, untimed
                        List<Double> times = new ArrayList<>();
                        for (int i = 1; i <= warmRuns; i++) {
                            resetOutputsOnly(toolDir);
                            double e = timed(toolDir, command(tool, operation, cacheDir), env(tool, cacheDir));
                            times.add(e);
                            System.out.printf("  %-16s run %d: %.2fs%n", tool, i, e);
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
        if (writeDocs) {
            if (!mode.equals("both") || !scenarioArg.equals("all")) {
                fail("--write-docs needs every scenario in both modes (java Bench.java all both [runs] --write-docs)");
            }
            writeDocs(results, warmRuns);
        }
    }

    /**
     * commons-lang3's sources aren't in this repo: each tool variant's src symlink points into this
     * checkout. Pinned to a commit, same as Regress.java (keep the two in step).
     */
    private static void fetchCommonsLang3() throws IOException, InterruptedException {
        Path cache = HERE.resolve("commons-lang3").resolve(".cache");
        Path src = cache.resolve("src");
        if (!Files.isDirectory(src.resolve(".git"))) {
            Files.createDirectories(cache);
            System.out.println("-- fetching commons-lang3 " + LANG3_TAG + " --");
            exec(cache, "git", "clone", "--quiet", "--depth", "1", "--branch", LANG3_TAG, LANG3_URL, "src");
        }
        String head = exec(src, "git", "rev-parse", "HEAD").strip();
        if (!head.equals(LANG3_COMMIT)) {
            fail(LANG3_TAG + " is " + head + ", expected " + LANG3_COMMIT + " - delete " + src + " and re-run");
        }
    }

    private static String exec(Path dir, String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) fail(String.join(" ", command) + " failed: " + out);
        return out;
    }

    private static List<String> command(String tool, String operation, Path cacheDir) {
        Path mavenRepo = cacheDir.resolve("maven-m2");
        return switch (tool) {
            case "kwenza" -> List.of("java", "-jar", kwenzaJar().toString(), operation);
            case "maven" -> operation.equals("package")
                    ? List.of("./mvnw", "-q", "-Dmaven.repo.local=" + mavenRepo, "-Dmaven.test.skip=true", "package")
                    : List.of("./mvnw", "-q", "-Dmaven.repo.local=" + mavenRepo, "test");
            case "gradle" -> List.of("./gradlew", operation.equals("package") ? "assemble" : "test");
            case "gradle-nodaemon" -> List.of("./gradlew", "--no-daemon", operation.equals("package") ? "assemble" : "test");
            default -> throw new IllegalArgumentException(tool);
        };
    }

    private static Map<String, String> env(String tool, Path cacheDir) {
        return switch (tool) {
            case "kwenza" -> Map.of("KWENZA_REPO", cacheDir.resolve("kwenza-m2").toString());
            case "gradle", "gradle-nodaemon" -> Map.of("GRADLE_USER_HOME", cacheDir.resolve("gradle-home").toString());
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
        System.out.printf("%-14s %-9s %-16s %10s %10s %10s%n",
                "scenario", "operation", "tool", "cold(s)", "min(s)", "median(s)");
        for (Result r : results) {
            System.out.printf("%-14s %-9s %-16s %10s %10s %10s%n",
                    r.scenario(), r.operation(), r.tool(), fmt(r.cold()), fmt(r.warmMin()), fmt(r.warmMedian()));
        }
    }

    // ---- --write-docs: the numbers, charts and commands behind docs/modules/ROOT/pages/performance.adoc ----

    private static final Map<String, String> DISPLAY = Map.of(
            "kwenza", "kwenza", "maven", "Maven", "gradle", "Gradle (daemon)", "gradle-nodaemon", "Gradle (no daemon)");
    private static final Map<String, String> COLOURS = Map.of(
            "kwenza", "#c4701a", "maven", "#4a6fa5", "gradle", "#2f8a6a", "gradle-nodaemon", "#8fc4ab");

    /**
     * Writes everything the page shows that comes from a run, so none of it is typed by hand and none
     * of it can drift from what the harness does: a partial (environment, tables, the exact command
     * per tool and operation) and two charts. The prose around it lives in the page.
     */
    private static void writeDocs(List<Result> results, int warmRuns) throws IOException, InterruptedException {
        Path docs = HERE.getParent().resolve("docs/modules/ROOT");
        Files.createDirectories(docs.resolve("partials"));
        Files.createDirectories(docs.resolve("images"));

        StringBuilder a = new StringBuilder();
        a.append("// Generated by `java Bench.java all both ").append(warmRuns)
                .append(" --write-docs` in benchmarks/. Do not edit by hand.\n\n");
        a.append("[cols=\"1,3\"]\n|===\n");
        a.append("|Measured on |").append(cpuModel()).append(", ").append(Runtime.getRuntime().availableProcessors())
                .append(" cores, ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append('\n');
        a.append("|Date |").append(java.time.LocalDate.now()).append('\n');
        a.append("|kwenza |").append(kwenzaVersion()).append(", commit `").append(gitHead()).append("`\n");
        a.append("|JDK |").append(System.getProperty("java.vm.vendor")).append(' ')
                .append(System.getProperty("java.runtime.version")).append(", the `java` on `PATH`, used by every tool\n");
        a.append("|Maven |").append(wrapperVersion("hello-world/maven/.mvn/wrapper/maven-wrapper.properties",
                "apache-maven-([\\d.]+)-bin")).append(" (wrapper)\n");
        a.append("|Gradle |").append(wrapperVersion("hello-world/gradle/gradle/wrapper/gradle-wrapper.properties",
                "gradle-([\\d.]+)-bin")).append(" (wrapper)\n");
        a.append("|Warm runs |").append(warmRuns).append(" timed runs per tool and operation, after one untimed run\n");
        a.append("|===\n\n");

        a.append("== Warm builds\n\nimage::benchmark-warm.svg[Median warm build time per tool and operation,role=benchmark-chart]\n\n");
        a.append(table(results, true));
        a.append("\n== Cold builds\n\nimage::benchmark-cold.svg[Cold build time per tool and operation,role=benchmark-chart]\n\n");
        a.append(table(results, false));

        a.append("\n== Exact commands\n\nRun from each tool's project directory. `<cache>` is that scenario's isolated cache "
                + "directory.\n\n[cols=\"2,1,4\"]\n|===\n|Operation |Tool |Command\n");
        for (ScenarioSpec sc : SCENARIOS) {
            Path cache = HERE.resolve(sc.name()).resolve(".cache");
            for (String op : sc.operations()) {
                for (String tool : TOOLS) {
                    String cmd = String.join(" ", command(tool, op, cache))
                            .replace(kwenzaJar().toString(), "kwenza.jar").replace(cache.toString(), "<cache>");
                    a.append('|').append(sc.name()).append(" / ").append(op).append('|').append(DISPLAY.get(tool))
                            .append("|`").append(cmd).append("`\n");
                }
            }
        }
        a.append("|===\n");
        Files.writeString(docs.resolve("partials/benchmark-results.adoc"), a.toString());
        Files.writeString(docs.resolve("images/benchmark-warm.svg"), chart(results, true));
        Files.writeString(docs.resolve("images/benchmark-cold.svg"), chart(results, false));
        System.out.println("wrote docs/modules/ROOT/{partials/benchmark-results.adoc,images/benchmark-*.svg}");
    }

    private static String table(List<Result> results, boolean warm) {
        StringBuilder t = new StringBuilder();
        t.append("[cols=\"").append(warm ? "2,1,1,1" : "2,1,1").append("\"]\n|===\n|Operation |Tool |")
                .append(warm ? "Median (s) |Fastest (s)" : "Time (s)").append('\n');
        for (Result r : results) {
            t.append('|').append(r.scenario()).append(" / ").append(r.operation()).append('|')
                    .append(DISPLAY.get(r.tool())).append('|')
                    .append(warm ? fmt(r.warmMedian()) + " |" + fmt(r.warmMin()) : fmt(r.cold())).append('\n');
        }
        return t.append("|===\n").toString();
    }

    /** A grouped horizontal bar chart, one group per scenario/operation, one bar per tool, shared scale. */
    private static String chart(List<Result> results, boolean warm) {
        int labelW = 190, barW = 420, barH = 16, gap = 5, groupGap = 18, top = 52, right = 70;
        Map<String, List<Result>> groups = new LinkedHashMap<>();
        for (Result r : results) groups.computeIfAbsent(r.scenario() + " / " + r.operation(), k -> new ArrayList<>()).add(r);
        double max = results.stream().mapToDouble(r -> warm ? r.warmMedian() : r.cold()).max().orElse(1);
        int groupH = TOOLS.size() * (barH + gap);
        int height = top + groups.size() * (groupH + groupGap) + 8;
        int width = labelW + barW + right;
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" width=\"").append(width).append("\" font-family=\"system-ui, sans-serif\" font-size=\"12\">\n");
        svg.append("<title>").append(warm ? "Median warm build time (seconds, lower is better)"
                : "Cold build time (seconds, lower is better)").append("</title>\n");
        svg.append("<rect width=\"100%\" height=\"100%\" rx=\"8\" fill=\"#ffffff\"/>\n");
        int lx = 12;
        for (String tool : TOOLS) {
            svg.append(String.format("<rect x=\"%d\" y=\"14\" width=\"12\" height=\"12\" rx=\"2\" fill=\"%s\"/>"
                    + "<text x=\"%d\" y=\"24\" fill=\"#222\">%s</text>\n", lx, COLOURS.get(tool), lx + 17, DISPLAY.get(tool)));
            lx += 17 + DISPLAY.get(tool).length() * 7 + 18;
        }
        svg.append(String.format("<text x=\"%d\" y=\"42\" fill=\"#666\">seconds, lower is better</text>\n", labelW));
        int y = top;
        for (var g : groups.entrySet()) {
            svg.append(String.format("<text x=\"%d\" y=\"%d\" text-anchor=\"end\" fill=\"#222\">%s</text>\n",
                    labelW - 10, y + groupH / 2 + 2, g.getKey()));
            int by = y;
            for (Result r : g.getValue()) {
                double v = warm ? r.warmMedian() : r.cold();
                double w = Math.max(1, barW * v / max);
                svg.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%.1f\" height=\"%d\" rx=\"2\" fill=\"%s\"/>"
                        + "<text x=\"%.1f\" y=\"%d\" fill=\"#222\">%.2f</text>\n",
                        labelW, by, w, barH, COLOURS.get(r.tool()), labelW + w + 6, by + 12, v));
                by += barH + gap;
            }
            y += groupH + groupGap;
        }
        return svg.append("</svg>\n").toString();
    }

    private static String cpuModel() throws IOException, InterruptedException {
        try {
            Process p = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).strip();
            if (p.waitFor() == 0 && !out.isEmpty()) return out;
        } catch (IOException ignored) {
            // not macOS
        }
        Path cpuinfo = Path.of("/proc/cpuinfo");
        if (Files.isReadable(cpuinfo)) {
            for (String line : Files.readAllLines(cpuinfo)) {
                if (line.startsWith("model name")) return line.substring(line.indexOf(':') + 1).strip();
            }
        }
        return System.getProperty("os.arch");
    }

    /** The version in kwenza's own manifest, the one place a release changes it. */
    private static String kwenzaVersion() throws IOException {
        var m = java.util.regex.Pattern.compile("(?m)^version\\s*=\\s*\"([^\"]+)\"")
                .matcher(Files.readString(HERE.getParent().resolve("kwenza.toml")));
        return m.find() ? m.group(1) : "unknown";
    }

    private static String gitHead() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").directory(HERE.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        return p.waitFor() == 0 ? out : "unknown";
    }

    private static String wrapperVersion(String relativePath, String regex) throws IOException {
        var m = java.util.regex.Pattern.compile(regex).matcher(Files.readString(HERE.resolve(relativePath)));
        return m.find() ? m.group(1) : "unknown";
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
