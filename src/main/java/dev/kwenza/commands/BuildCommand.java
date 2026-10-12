package dev.kwenza.commands;

import dev.kwenza.compiler.CompileContext;

/** {@code kwenza build}: resolves dependencies and compiles src/main/java. Produces no artifact. */
public final class BuildCommand {
    private final CompileContext compile;

    public BuildCommand(CompileContext compile) {
        this.compile = compile;
    }

    public void run() throws Exception {
        compile.ensureCompiled();
    }
}
