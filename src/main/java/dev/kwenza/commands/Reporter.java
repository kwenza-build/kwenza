package dev.kwenza.commands;

/**
 * What a command needs in order to tell the user what it's doing - kept as an interface so
 * {@code BuildState} and the {@code *Command} classes never know whether that's a real terminal,
 * a test double, or something else entirely. The real, console-specific implementation
 * ({@code ConsoleReporter}) lives in {@code dev.kwenza.cli}, deliberately: commands are just
 * classes with methods that do tasks, not anything that knows about terminals or ANSI colors.
 */
public interface Reporter {

    /** kwenza's own build-progress messages (resolving, compiling, packaging, ...). */
    void info(String message);

    /** Fatal errors reported by a command. */
    void error(String message);

    /** Extra, --verbose-only detail (e.g. one line per resolved dependency) - dimmer than info(). */
    void detail(String message);
}
