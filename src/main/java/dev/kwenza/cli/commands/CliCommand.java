package dev.kwenza.cli.commands;

import dev.kwenza.cli.Session;
import dev.kwenza.context.Result;

import java.io.IOException;

import java.util.List;

/**
 * The command-line side of one kwenza command: its name, its one-line summary, the options it
 * accepts, and how to turn a parsed {@link Invocation} into a call on the command that does the
 * work (in {@code dev.kwenza.commands}). Nothing here does any building itself.
 *
 * <p>Every implementation has a manual page, {@code docs/modules/ROOT/pages/commands/kwenza-<name>.adoc},
 * which kwenza.dev publishes and {@code kwenza help <name>} prints. {@code CliDocsTest} keeps the
 * two in step: same summary, same options.
 */
public interface CliCommand {

    String name();

    /** One line, shown by {@code kwenza --list} and {@code kwenza <name> --help}; the manual page's NAME line repeats it. */
    String about();

    /** Shown in the usage {@code kwenza} prints with no arguments; everything else only under {@code --list}. */
    default boolean common() {
        return false;
    }

    /** This command's own options, not counting the global ones ({@code --verbose}, {@code --help}). */
    default List<CliOption> options() {
        return List.of();
    }

    /** The name of the single optional positional argument it takes (e.g. {@code command} for help), or null. */
    default String argumentName() {
        return null;
    }

    /**
     * Whether it works on the project in the working directory. If so, {@code Cli} loads that
     * project before running it (failing with a clear message if there isn't one), so
     * {@link Session#project()} is always there by the time {@link #exec} runs.
     */
    default boolean needsProject() {
        return true;
    }

    /** Runs it. Expected failures come back as a failed Result; anything thrown is a kwenza bug. */
    Result<Void> exec(Session session, Invocation invocation) throws IOException, InterruptedException;
}
