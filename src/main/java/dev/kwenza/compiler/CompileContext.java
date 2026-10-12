package dev.kwenza.compiler;

import dev.kwenza.context.Memo;
import dev.kwenza.resolver.Resolver;

/**
 * What an invocation has compiled so far, shared by every command in its chain so
 * {@code kwenza build run} compiles once. State only: {@code BuildCommand} does the compiling.
 * Each memo holds the resolution the classes were compiled against.
 */
public final class CompileContext {
    private final Memo<Resolver.Resolution> main = new Memo<>();
    private final Memo<Resolver.Resolution> tests = new Memo<>();

    /** {@code src/main/java}, compiled against {@code [dependencies]} only. */
    public Memo<Resolver.Resolution> main() {
        return main;
    }

    /** {@code src/test/java}, compiled against both scopes. */
    public Memo<Resolver.Resolution> tests() {
        return tests;
    }

    /** Whatever was compiled is gone ({@code clean} deleted it): the next build must compile again. */
    public void forget() {
        main.forget();
        tests.forget();
    }
}
