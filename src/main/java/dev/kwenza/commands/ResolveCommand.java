package dev.kwenza.commands;

import java.io.IOException;

/** `kwenza resolve` - refreshes kwenza.lock explicitly, without compiling or packaging anything. */
public final class ResolveCommand {
    private ResolveCommand() {
    }

    public static void run(BuildState state) throws IOException {
        state.ensureResolved();
    }
}
