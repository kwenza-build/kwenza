package dev.kwenza.toml;

/**
 * How the recursive-descent parser bails out of a syntax error from any depth. Never leaves this
 * package: {@link Toml#parse} turns it into a failed Result, the one place AGENTS.md's "errors are
 * values" rule allows an exception for an expected failure. Stackless, since the message is all
 * that's ever used.
 */
final class TomlParseException extends RuntimeException {
    TomlParseException(String message) {
        super(message, null, false, false);
    }
}
