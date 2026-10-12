package dev.kwenza.cli;

import dev.kwenza.context.Reporter;

import java.io.PrintStream;

/**
 * The real {@code Reporter} the actual kwenza CLI uses: prints to stderr, so a running
 * application's own stdout stays exactly what it printed, and coloured (when {@link Style} allows)
 * so kwenza's lines stand apart from it. Detail lines only print under {@code --verbose}.
 */
public final class ConsoleReporter implements Reporter {
    private final PrintStream err;
    private final Style style;
    private final boolean verbose;

    public ConsoleReporter(PrintStream err, Style style, boolean verbose) {
        this.err = err;
        this.style = style;
        this.verbose = verbose;
    }

    @Override
    public void info(String message) {
        err.println(style.info(message));
    }

    @Override
    public void error(String message) {
        err.println(style.error(message));
    }

    @Override
    public void detail(String message) {
        if (verbose) {
            err.println(style.detail(message));
        }
    }
}
