package dev.kwenza.context;

/**
 * A user-facing failure with a specific, actionable message - reported via {@code error: ...} and
 * exit code 1. Thrown from any layer (a missing manifest, a failed compile, a misconfigured test
 * setup), which is why it lives here and not in {@code cli}.
 */
public final class KwenzaException extends RuntimeException {
    public KwenzaException(String message) {
        super(message);
    }
}
