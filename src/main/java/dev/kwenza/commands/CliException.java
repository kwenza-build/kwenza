package dev.kwenza.commands;

/** A user-facing failure with a specific, actionable message - reported via {@code error: ...} and exit code 1. */
public final class CliException extends RuntimeException {
    public CliException(String message) {
        super(message);
    }
}
