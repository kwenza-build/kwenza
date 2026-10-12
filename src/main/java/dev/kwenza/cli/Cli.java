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
import dev.kwenza.commands.CommandFailedException;
import dev.kwenza.context.GlobalContext;
import dev.kwenza.context.KwenzaException;
import dev.kwenza.context.Reporter;

import java.io.PrintStream;
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

    private final PrintStream out;
    private final PrintStream err;
    private final Style style;

    public Cli(PrintStream out, PrintStream err, Style style) {
        this.out = out;
        this.err = err;
        this.style = style;
    }

    /** Runs a whole command line and returns the process exit code. Never calls System.exit itself. */
    public int run(String[] args) {
        CommandLine line;
        try {
            line = parse(args);
        } catch (KwenzaException e) {
            new ConsoleReporter(err, style, false).error("error: " + e.getMessage());
            return 1;
        }
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
                invocation.command().exec(session, invocation);
            }
            return 0;
        } catch (CommandFailedException e) {
            // The subprocess already printed whatever it needed to - just propagate its exit
            // code, no second "error: ..." message on top.
            return e.exitCode;
        } catch (Exception e) {
            reporter.error("error: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }

    public static CommandLine parse(String[] args) {
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
                CliOption option = find(allowed, name);
                if (option == null) {
                    throw new KwenzaException(command == null
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
                        throw new KwenzaException("option '" + name + "' needs a value: " + option.display());
                    }
                } else if (inlineValue != null) {
                    throw new KwenzaException("option '" + name + "' doesn't take a value");
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
                command = find(arg);
                options = new LinkedHashMap<>();
                argument = null;
                commandHelp = false;
            }
        }
        if (command != null) {
            invocations.add(new Invocation(command, Map.copyOf(options), argument, commandHelp));
        }
        return new CommandLine(verbose, topLevelHelp, version, list, List.copyOf(invocations));
    }

    /** A command's own options plus the global ones, in the order its help lists them. */
    public static List<CliOption> commandOptions(CliCommand command) {
        List<CliOption> all = new ArrayList<>(command.options());
        all.addAll(GLOBAL_OPTIONS);
        return all;
    }

    /** The command called {@code name}; fails with a pointer to {@code --list} if there's none. */
    public static CliCommand find(String name) {
        for (CliCommand command : COMMANDS) {
            if (command.name().equals(name)) {
                return command;
            }
        }
        throw new KwenzaException("no such command: `" + name + "`\n\nView all commands with `kwenza --list`");
    }

    private static CliOption find(List<CliOption> options, String name) {
        for (CliOption option : options) {
            if (option.matches(name)) {
                return option;
            }
        }
        return null;
    }
}
