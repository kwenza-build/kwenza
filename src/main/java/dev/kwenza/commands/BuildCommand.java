package dev.kwenza.commands;

/** `kwenza build` - resolves dependencies and compiles src/main/java. Produces no artifact. */
public final class BuildCommand {
    private BuildCommand() {
    }

    public static void run(BuildState state) throws Exception {
        state.ensureCompiled();
    }
}
