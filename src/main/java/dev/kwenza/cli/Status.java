package dev.kwenza.cli;

/**
 * Prints kwenza's own status/error messages to stderr, visually distinct from a running
 * application's own output when color is appropriate. Colors are suppressed automatically when
 * stdout isn't an actual terminal (piped/redirected) or when NO_COLOR is set - see
 * https://no-color.org/.
 */
final class Status {
    private static final char ESC = 27;
    private static final String CYAN = ESC + "[36m";
    private static final String RED = ESC + "[31m";
    private static final String GRAY = ESC + "[90m";
    private static final String RESET = ESC + "[0m";
    private static final boolean COLOR_ENABLED =
            System.console() != null && System.getenv("NO_COLOR") == null;

    private Status() {
    }

    /** kwenza's own build-progress messages (resolving, compiling, packaging, ...). */
    static void info(String message) {
        System.err.println(COLOR_ENABLED ? CYAN + message + RESET : message);
    }

    /** Fatal errors reported by the CLI itself. */
    static void error(String message) {
        System.err.println(COLOR_ENABLED ? RED + message + RESET : message);
    }

    /** Extra, --verbose-only detail (e.g. one line per resolved dependency) - dimmer than info(). */
    static void detail(String message) {
        System.err.println(COLOR_ENABLED ? GRAY + message + RESET : message);
    }
}
