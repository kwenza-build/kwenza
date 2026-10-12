package dev.kwenza.context;

import java.io.IOException;

/**
 * One remembered result, for the state an invocation's chained commands share: the first
 * successful computation is kept and returned from then on, a failure is never kept (it ends the
 * chain anyway), and {@link #forget()} drops it when it stops being true (`clean` deleting the
 * output it described). Holds state only; what to compute is always the caller's.
 */
public final class Memo<T> {

    /** How to compute the value when there's none yet. */
    public interface Compute<T> {
        Result<T> compute() throws IOException;
    }

    private T value;

    public Result<T> get(Compute<T> compute) throws IOException {
        if (value != null) {
            return Result.ok(value);
        }
        Result<T> result = compute.compute();
        if (result.isOk()) {
            value = result.get();
        }
        return result;
    }

    public void forget() {
        value = null;
    }
}
