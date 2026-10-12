package dev.kwenza.cli;

import dev.kwenza.cli.commands.BuildCli;
import dev.kwenza.cli.commands.CleanCli;
import dev.kwenza.cli.commands.CliCommand;
import dev.kwenza.cli.commands.CliOption;
import dev.kwenza.cli.commands.HelpCli;
import dev.kwenza.cli.commands.Invocation;
import dev.kwenza.cli.commands.PackageCli;
import dev.kwenza.cli.commands.ResolveCli;
import dev.kwenza.cli.commands.RunCli;
import dev.kwenza.cli.commands.TestCli;
import dev.kwenza.cli.commands.VersionCli;
import dev.kwenza.cli.help.Help;
import dev.kwenza.context.GlobalContext;
import dev.kwenza.context.Failure;
import dev.kwenza.context.Reporter;
import dev.kwenza.context.Result;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns argv into commands and runs them: {@code kwenza [options] <command> [options]
 * [<command> [options]]...}. Knows the full set of commands and the global options, and nothing
 * about what any command does.
 *
 * <p>Every argument is parsed before any command runs, so a typo anywhere in a chain fails
 * before {@code clean} has deleted anything.
 */
public final class Cli {

    /** In the order {@code --list} shows them. */
    public static final List<CliCommand> COMMANDS = List.of(
            new BuildCli(), new TestCli(), new RunCli(), new PackageCli(), new CleanCli(),
            new ResolveCli(), new HelpCli(), new VersionCli());

    // Accepted anywhere: before the first command, or after any of them.
    public static final CliOption VERBOSE = CliOption.flag("--verbose", "-v",
            "Print extra detail: resolved dependencies, full java commands");
    public static final CliOption HELP = CliOption.flag("--help", "-h",
            "Print help");
    // Accepted only before the first command: each is a whole request on its own.
    public static final CliOption VERSION = CliOption.flag("--version", "-V",
            "Print version information and exit");
    public static final CliOption LIST = CliOption.flag("--list", null,
            "List all commands");

    public static final List<CliOption> GLOBAL_OPTIONS = List.of(VERBOSE, HELP);
    public static final List<CliOption> TOP_LEVEL_OPTIONS = List.of(VERSION, LIST, VERBOSE, HELP);

    /** What the command line asked for, before anything runs. */
    public record CommandLine(boolean verbose, boolean help, boolean version, boolean list,
                              List<Invocation> invocations) {
    }

    /** Where to report kwenza's own bugs. */
    static final String ISSUES_URL = "https://github.com/kwenza-build/kwenza/issues";
    /** Rust's exit code for a panic: kwenza itself went wrong, not the user's project. */
    static final int BUG_EXIT_CODE = 101;

    private final PrintStream out;
    private final PrintStream err;
    private final Style style;
    private final List<CliCommand> commands;

    public Cli(PrintStream out, PrintStream err, Style style) {
        this(out, err, style, COMMANDS);
    }

    /** With a different set of commands, so tests can exercise every kind of outcome. */
    Cli(PrintStream out, PrintStream err, Style style, List<CliCommand> commands) {
        this.out = out;
        this.err = err;
        this.style = style;
        this.commands = commands;
    }

    /**
     * Runs a whole command line and returns the process exit code: 0, 1 (a problem the user can
     * fix), 2 (a bad command line), 101 (a kwenza bug), or a run/test child process's own code.
     * Never calls System.exit itself.
     */
    public int run(String[] args) {
        Result<CommandLine> parsed = parse(commands, args);
        if (parsed instanceof Result.Err<?> failed) {
            return report(new ConsoleReporter(err, style, false), failed.failure());
        }
        CommandLine line = parsed.get();
        Reporter reporter = new ConsoleReporter(err, style, line.verbose());
        Help help = new Help(out, style);
        if (line.version()) {
            help.printVersion();
            return 0;
        }
        if (line.list()) {
            help.printList();
            return 0;
        }
        if (line.help() || line.invocations().isEmpty()) {
            help.printUsage();
            return 0;
        }
        for (Invocation invocation : line.invocations()) {
            if (invocation.help()) {
                help.printCommandHelp(invocation.command());
                return 0;
            }
        }

        Session session = new Session(GlobalContext.fromEnvironment(reporter), help);
        try {
            for (Invocation invocation : line.invocations()) {
                Result<Void> result = invocation.command().needsProject() ? session.loadProject() : Result.ok();
                if (result.isOk()) {
                    result = invocation.command().exec(session, invocation);
                }
                if (result instanceof Result.Err<?> failed) {
                    return report(reporter, failed.failure());
                }
            }
            return 0;
        } catch (IOException | UncheckedIOException e) {
            // The environment failed underneath kwenza (a disk error, a permission): not a bug,
            // and not something a Result could have described any better than the exception does.
            reporter.error("error: " + describe(e instanceof UncheckedIOException u ? u.getCause() : e));
            return Failure.USER_ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reporter.error("error: interrupted");
            return Failure.USER_ERROR;
        } catch (RuntimeException | Error e) {
            // Every failure kwenza expects comes back as a Result, so anything thrown is a bug.
            reporter.error("error: internal error: " + e);
            reporter.error("this is a bug in kwenza - please report it at " + ISSUES_URL
                    + (line.verbose() ? "" : " (rerun with --verbose for a stack trace)"));
            if (line.verbose()) {
                e.printStackTrace(err);
            }
            return BUG_EXIT_CODE;
        }
    }

    /** One {@code error:} line per problem (none for a child process that already spoke); returns its exit code. */
    private static int report(Reporter reporter, Failure failure) {
        for (String problem : failure.problems()) {
            reporter.error("error: " + problem);
        }
        return failure.exitCode();
    }

    private static String describe(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    public static Result<CommandLine> parse(String[] args) {
        return parse(COMMANDS, args);
    }

    private static Result<CommandLine> parse(List<CliCommand> commands, String[] args) {
        boolean verbose = false;
        boolean topLevelHelp = false;
        boolean version = false;
        boolean list = false;
        List<Invocation> invocations = new ArrayList<>();

        CliCommand command = null;
        Map<CliOption, String> options = null;
        String argument = null;
        boolean commandHelp = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-") && arg.length() > 1) {
                int eq = arg.indexOf('=');
                String name = eq > 0 ? arg.substring(0, eq) : arg;
                String inlineValue = eq > 0 ? arg.substring(eq + 1) : null;
                List<CliOption> allowed = command == null ? TOP_LEVEL_OPTIONS : commandOptions(command);
                CliOption option = findOption(allowed, name);
                if (option == null) {
                    return usage(command == null
                            ? "unknown option '" + name + "'\n\nSee `kwenza --help` for the options kwenza takes."
                            : "unknown option '" + name + "' for `kwenza " + command.name() + "`\n\n"
                              + "See `kwenza " + command.name() + " --help` for the options it takes.");
                }
                String value = "";
                if (option.takesValue()) {
                    if (inlineValue != null) {
                        value = inlineValue;
                    } else if (i + 1 < args.length) {
                        value = args[++i];
                    } else {
                        return usage("option '" + name + "' needs a value: " + option.display());
                    }
                } else if (inlineValue != null) {
                    return usage("option '" + name + "' doesn't take a value");
                }

                if (option == VERBOSE) {
                    verbose = true;
                } else if (option == HELP) {
                    if (command == null) {
                        topLevelHelp = true;
                    } else {
                        commandHelp = true;
                    }
                } else if (option == VERSION) {
                    version = true;
                } else if (option == LIST) {
                    list = true;
                } else {
                    options.put(option, value);
                }
            } else if (command != null && command.argumentName() != null && argument == null) {
                argument = arg;
            } else {
                if (command != null) {
                    invocations.add(new Invocation(command, Map.copyOf(options), argument, commandHelp));
                }
                Result<CliCommand> found = find(commands, arg);
                if (found instanceof Result.Err<?> failed) {
                    return failed.castErr();
                }
                command = found.get();
                options = new LinkedHashMap<>();
                argument = null;
                commandHelp = false;
            }
        }
        if (command != null) {
            invocations.add(new Invocation(command, Map.copyOf(options), argument, commandHelp));
        }
        return Result.ok(new CommandLine(verbose, topLevelHelp, version, list, List.copyOf(invocations)));
    }

    /** A command's own options plus the global ones, in the order its help lists them. */
    public static List<CliOption> commandOptions(CliCommand command) {
        List<CliOption> all = new ArrayList<>(command.options());
        all.addAll(GLOBAL_OPTIONS);
        return all;
    }

    /** The command called {@code name}; a usage failure pointing at {@code --list} if there's none. */
    public static Result<CliCommand> find(String name) {
        return find(COMMANDS, name);
    }

    private static Result<CliCommand> find(List<CliCommand> commands, String name) {
        for (CliCommand command : commands) {
            if (command.name().equals(name)) {
                return Result.ok(command);
            }
        }
        return usage("no such command: `" + name + "`\n\nView all commands with `kwenza --list`");
    }

    private static <T> Result<T> usage(String problem) {
        return Result.err(Failure.usage(problem));
    }

    private static CliOption findOption(List<CliOption> options, String name) {
        for (CliOption option : options) {
            if (option.matches(name)) {
                return option;
            }
        }
        return null;
    }
}
