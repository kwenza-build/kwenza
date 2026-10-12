package dev.kwenza.commands;

/**
 * Thrown by a command whose spawned subprocess (run/test) exited non-zero. The subprocess has
 * already printed whatever it needed to, so the CLI just propagates the same exit code silently,
 * without printing a second "error: ..." message on top.
 */
public final class CommandFailedException extends RuntimeException {
    public final int exitCode;

    CommandFailedException(int exitCode) {
        this.exitCode = exitCode;
    }
}
