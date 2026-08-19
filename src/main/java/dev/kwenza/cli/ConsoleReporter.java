package dev.kwenza.cli;

import dev.kwenza.commands.Reporter;

/**
 * The real {@code Reporter} the actual kwenza CLI uses: prints to stderr, visually distinct from
 * a running application's own output when color is appropriate. Colors are suppressed
 * automatically when stdout isn't an actual terminal (piped/redirected) or when NO_COLOR is set -
 * see https://no-color.org/.
 */
public final class ConsoleReporter implements Reporter {
    private static final char ESC = 27;
    private static final String CYAN = ESC + "[36m";
    private static final String RED = ESC + "[31m";
    private static final String GRAY = ESC + "[90m";
    private static final String RESET = ESC + "[0m";
    private static final boolean COLOR_ENABLED =
            System.console() != null && System.getenv("NO_COLOR") == null;

    @Override
    public void info(String message) {
        System.err.println(COLOR_ENABLED ? CYAN + message + RESET : message);
    }

    @Override
    public void error(String message) {
        System.err.println(COLOR_ENABLED ? RED + message + RESET : message);
    }

    @Override
    public void detail(String message) {
        System.err.println(COLOR_ENABLED ? GRAY + message + RESET : message);
    }
}
