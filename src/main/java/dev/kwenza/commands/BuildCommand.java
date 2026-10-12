package dev.kwenza.commands;

import dev.kwenza.compiler.CompileContext;
import dev.kwenza.context.Result;

import java.io.IOException;

/** {@code kwenza build}: resolves dependencies and compiles src/main/java. Produces no artifact. */
public final class BuildCommand {
    private final CompileContext compile;

    public BuildCommand(CompileContext compile) {
        this.compile = compile;
    }

    public Result<Void> run() throws IOException {
        return compile.ensureCompiled().map(resolution -> null);
    }
}
