package dev.kwenza.resolver;

import dev.kwenza.context.Memo;

/**
 * What an invocation has resolved so far, shared by every command in its chain so
 * {@code kwenza build run} resolves once. State only: {@code ResolveCommand} does the resolving.
 */
public final class ResolveContext {
    private final Memo<Resolver.Resolution> merged = new Memo<>();
    private final Memo<Resolver.Resolution> main = new Memo<>();

    /** {@code [dependencies]} and {@code [dev-dependencies]} together, backed by kwenza.lock. */
    public Memo<Resolver.Resolution> merged() {
        return merged;
    }

    /** {@code [dependencies]} only, for anything shipped. */
    public Memo<Resolver.Resolution> main() {
        return main;
    }
}
