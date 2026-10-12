package dev.kwenza.cli;

import dev.kwenza.context.Timing;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        int exitCode;
        // Must be the first thing that runs: loading Timing here captures the true main() entry time.
        try (var timing = Timing.start()) {
            exitCode = new Cli(System.out, System.err, Style.detect()).run(args);
        }
        // After the timing scope closes, so its report comes first. Force immediate exit rather
        // than waiting on any lingering non-daemon threads (e.g. the HTTP client's internal
        // executor) to wind down.
        System.exit(exitCode);
    }
}
