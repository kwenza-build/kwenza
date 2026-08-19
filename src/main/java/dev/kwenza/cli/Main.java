package dev.kwenza.cli;

import dev.kwenza.commands.BuildCommand;
import dev.kwenza.commands.BuildState;
import dev.kwenza.commands.CleanCommand;
import dev.kwenza.commands.CliException;
import dev.kwenza.commands.CommandFailedException;
import dev.kwenza.commands.PackageCommand;
import dev.kwenza.commands.ResolveCommand;
import dev.kwenza.commands.Reporter;
import dev.kwenza.commands.RunCommand;
import dev.kwenza.commands.TestCommand;
import dev.kwenza.commands.Timing;
import dev.kwenza.manifest.KwenzaManifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Main {

    public static void main(String[] args) {
        Timing.init(); // must be the very first statement - captures the true main() entry time
        Reporter reporter = new ConsoleReporter();
        try {
            run(args, reporter);
        } catch (CommandFailedException e) {
            // The subprocess already printed whatever it needed to via inheritIO() - just
            // propagate its exit code, no second "error: ..." message on top.
            Timing.report();
            System.exit(e.exitCode);
        } catch (CliException e) {
            reporter.error("error: " + e.getMessage());
            Timing.report();
            System.exit(1);
        } catch (Exception e) {
            reporter.error("error: " + (e.getMessage() != null ? e.getMessage() : e));
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

    private static void run(String[] args, Reporter reporter) throws Exception {
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
                reporter.error("error: unknown command '" + command + "'");
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
        BuildState state = new BuildState(manifest, projectRoot, lockPath, targetDir, localRepo, update, verbose, reporter);

        for (String command : commands) {
            switch (command) {
                case "resolve" -> ResolveCommand.run(state);
                case "build" -> BuildCommand.run(state);
                case "test" -> TestCommand.run(state, verbose);
                case "package" -> PackageCommand.run(state);
                case "run" -> RunCommand.run(state, verbose);
                case "clean" -> CleanCommand.run(state);
            }
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
}
