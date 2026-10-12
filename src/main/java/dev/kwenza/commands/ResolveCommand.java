package dev.kwenza.commands;

import dev.kwenza.resolver.ResolveContext;

import java.io.IOException;

/**
 * {@code kwenza resolve}: makes sure kwenza.lock matches the manifest, resolving only if it
 * doesn't, without compiling or packaging anything.
 */
public final class ResolveCommand {
    private final ResolveContext resolve;

    public ResolveCommand(ResolveContext resolve) {
        this.resolve = resolve;
    }

    public void run() throws IOException {
        resolve.ensureResolved();
    }
}
