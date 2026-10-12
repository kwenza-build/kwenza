package dev.kwenza.cli;

import dev.kwenza.context.Timing;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Timing.init(); // must be the very first statement - captures the true main() entry time
        int exitCode = new Cli(System.out, System.err, Style.detect()).run(args);
        Timing.report();
        // Force immediate exit rather than waiting on any lingering non-daemon
        // threads (e.g. the HTTP client's internal executor) to wind down.
        System.exit(exitCode);
    }
}
