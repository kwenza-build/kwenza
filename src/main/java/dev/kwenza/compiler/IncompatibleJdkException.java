package dev.kwenza.compiler;

/** Thrown when the requested [package] java-version is newer than the JDK running kwenza. */
public final class IncompatibleJdkException extends RuntimeException {
    public IncompatibleJdkException(String message) {
        super(message);
    }
}
