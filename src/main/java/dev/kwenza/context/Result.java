package dev.kwenza.context;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The outcome of anything that can fail in a way kwenza designed for: either a value, or a
 * {@link Failure} saying why not. Expected failures (a bad manifest, a checksum mismatch, a failed
 * compile, a test JVM exiting 1) are values of this type, never exceptions; an exception means
 * kwenza itself has a bug, and the CLI reports it as one (exit 101). See AGENTS.md, "Errors are
 * values".
 *
 * <p>Without a {@code ?} operator, propagating an error is two lines:
 * <pre>{@code
 * Result<Manifest> manifest = load(path);
 * if (manifest instanceof Result.Err<?> err) return err.castErr();
 * }</pre>
 * or, for a straight chain, {@code map}/{@code flatMap}.
 */
public sealed interface Result<T> permits Result.Ok, Result.Err {

    record Ok<T>(T value) implements Result<T> {
    }

    record Err<T>(Failure failure) implements Result<T> {
    }

    static <T> Result<T> ok(T value) {
        return new Ok<>(value);
    }

    static Result<Void> ok() {
        return new Ok<>(null);
    }

    /** A user-fixable failure with one problem. */
    static <T> Result<T> err(String problem) {
        return new Err<>(Failure.user(problem));
    }

    static <T> Result<T> err(Failure failure) {
        return new Err<>(failure);
    }

    /** Ok if every one is; otherwise every problem from every Err, not just the first. */
    static <T> Result<List<T>> all(List<Result<T>> results) {
        List<T> values = new ArrayList<>();
        Failure failure = null;
        for (Result<T> result : results) {
            if (result instanceof Err<T> err) {
                failure = failure == null ? err.failure() : failure.plus(err.failure());
            } else if (failure == null) {
                values.add(((Ok<T>) result).value());
            }
        }
        return failure == null ? ok(values) : err(failure);
    }

    default boolean isOk() {
        return this instanceof Ok;
    }

    /** The value; calling it on an Err is a bug in the caller. */
    default T get() {
        if (this instanceof Ok<T> ok) {
            return ok.value();
        }
        throw new IllegalStateException("get() on a failed Result: " + failure().problems());
    }

    /** The failure; calling it on an Ok is a bug in the caller. */
    default Failure failure() {
        if (this instanceof Err<T> err) {
            return err.failure();
        }
        throw new IllegalStateException("failure() on a successful Result");
    }

    /** This Err as a Result of any other type, for passing a failure up. Calling it on an Ok is a bug. */
    @SuppressWarnings("unchecked")
    default <U> Result<U> castErr() {
        if (this instanceof Err) {
            return (Result<U>) this;
        }
        throw new IllegalStateException("castErr() on a successful Result");
    }

    default <U> Result<U> map(Function<? super T, ? extends U> f) {
        return this instanceof Ok<T> ok ? ok(f.apply(ok.value())) : castErr();
    }

    default <U> Result<U> flatMap(Function<? super T, Result<U>> f) {
        return this instanceof Ok<T> ok ? f.apply(ok.value()) : castErr();
    }
}
