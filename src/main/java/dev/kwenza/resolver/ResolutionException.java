package dev.kwenza.resolver;

public final class ResolutionException extends RuntimeException {
    public ResolutionException(String message) {
        super(message);
    }

    public ResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
