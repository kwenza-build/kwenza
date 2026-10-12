package dev.kwenza.cli.help;

import dev.kwenza.cli.Cli;
import dev.kwenza.cli.Style;
import dev.kwenza.cli.commands.CliCommand;
import dev.kwenza.cli.commands.CliOption;
import dev.kwenza.context.KwenzaException;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything kwenza prints about itself rather than about a build: usage, the command list, a
 * command's short {@code --help}, its full manual page, and the version. All of it goes to
 * stdout, since it's the output that was asked for ({@code kwenza help build | less} works).
 *
 * <p>The short forms are generated from the {@link CliCommand}s; the manual pages are the
 * AsciiDoc files under {@code docs/modules/ROOT/pages/commands/}, bundled into the jar and
 * rendered by {@link ManualRenderer}, so the CLI and kwenza.dev print the same text.
 */
public final class Help {
    /** Where the manual pages sit on the classpath, as {@code kwenza-<command>.adoc}. */
    public static final String MANUAL_DIR = "/dev/kwenza/cli/help/";

    private final PrintStream out;
    private final Style style;

    public Help(PrintStream out, Style style) {
        this.out = out;
        this.style = style;
    }

    public void printUsage() {
        out.println("A simple, declarative build tool for the JVM");
        out.println();
        out.println(style.heading("Usage:") + " " + style.literal("kwenza") + " "
                + style.placeholder("[OPTIONS] <COMMAND> [<COMMAND>...]"));
        out.println();
        printOptions(Cli.TOP_LEVEL_OPTIONS);
        out.println();
        out.println(style.heading("Commands:"));
        List<String[]> rows = new ArrayList<>();
        for (CliCommand command : Cli.COMMANDS) {
            if (command.common()) {
                rows.add(new String[]{command.name(), command.about()});
            }
        }
        rows.add(new String[]{"...", "See all commands with " + style.literal("--list")});
        printRows(rows);
        out.println();
        out.println("Commands run in the order given, and share their work:");
        out.println(style.literal("`kwenza clean build run`") + " resolves and compiles once.");
        out.println();
        out.println("See " + style.literal("`kwenza help <command>`") + " for more information on a specific command.");
    }

    public void printList() {
        out.println(style.heading("Commands:"));
        List<String[]> rows = new ArrayList<>();
        for (CliCommand command : Cli.COMMANDS) {
            rows.add(new String[]{command.name(), command.about()});
        }
        printRows(rows);
    }

    public void printCommandHelp(CliCommand command) {
        out.println(command.about());
        out.println();
        String argument = command.argumentName() != null ? " [<" + command.argumentName().toUpperCase() + ">]" : "";
        out.println(style.heading("Usage:") + " " + style.literal("kwenza " + command.name()) + " "
                + style.placeholder("[OPTIONS]" + argument));
        out.println();
        printOptions(Cli.commandOptions(command));
        out.println();
        out.println("Run " + style.literal("`kwenza help " + command.name() + "`") + " for more detailed information.");
    }

    /** Prints {@code command}'s full manual page. */
    public void printManual(String commandName) {
        CliCommand command = Cli.find(commandName);
        out.print(new ManualRenderer(style).render(manualSource(command)));
    }

    public void printVersion() {
        String version = Cli.class.getPackage().getImplementationVersion();
        out.println("kwenza " + (version != null ? version : "(development build)"));
    }

    /** The AsciiDoc source of {@code command}'s manual page, as bundled in the jar. */
    public static String manualSource(CliCommand command) {
        String resource = MANUAL_DIR + "kwenza-" + command.name() + ".adoc";
        try (InputStream in = Help.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new KwenzaException("this kwenza build has no manual page for `" + command.name()
                        + "` bundled - read it at https://kwenza.dev/docs/commands/kwenza-" + command.name() + ".html");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new KwenzaException("couldn't read " + resource + ": " + e.getMessage());
        }
    }

    private void printOptions(List<CliOption> options) {
        out.println(style.heading("Options:"));
        int width = 0;
        for (CliOption option : options) {
            width = Math.max(width, optionColumn(option).length());
        }
        for (CliOption option : options) {
            // Long-only options line up with the long names of ones that have a short form.
            String indent = option.shortName() == null ? "      " : "  ";
            String names = option.shortName() == null ? option.longName() : option.shortName() + ", " + option.longName();
            String value = option.takesValue() ? " " + style.placeholder("<" + option.valueName() + ">") : "";
            String padding = " ".repeat(width - optionColumn(option).length() + 2);
            out.println(indent + style.literal(names) + value + padding + option.help());
        }
    }

    /** How wide an option's first column is, before any styling. */
    private static String optionColumn(CliOption option) {
        return (option.shortName() == null ? "    " : "") + option.display();
    }

    /** Two aligned columns, the first one as literals. Pads before styling, so escapes don't skew the widths. */
    private void printRows(List<String[]> rows) {
        int width = 0;
        for (String[] row : rows) {
            width = Math.max(width, row[0].length());
        }
        for (String[] row : rows) {
            String padding = " ".repeat(width - row[0].length() + 2);
            out.println("  " + style.literal(row[0]) + padding + row[1]);
        }
    }
}
