package dev.kwenza.context;

/**
 * What the rest of kwenza needs in order to tell the user what it's doing - kept as an interface
 * so contexts and commands never know whether that's a real terminal, a test double, or something
 * else entirely. The real, console-specific implementation ({@code ConsoleReporter}) lives in
 * {@code dev.kwenza.cli}, deliberately: nothing below the CLI knows about terminals or ANSI colors.
 */
public interface Reporter {

    /** kwenza's own build-progress messages (resolving, compiling, packaging, ...). */
    void info(String message);

    /** Fatal errors reported by a command. */
    void error(String message);

    /**
     * Extra detail (e.g. one line per resolved dependency). Callers always report it; whether it's
     * shown is the implementation's call ({@code ConsoleReporter} only prints it under
     * {@code --verbose}), so verbosity never has to be threaded through the code that produces it.
     */
    void detail(String message);
}
