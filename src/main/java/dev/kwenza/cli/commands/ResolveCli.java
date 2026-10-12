package dev.kwenza.cli.commands;

import dev.kwenza.cli.Session;
import dev.kwenza.commands.ResolveCommand;
import dev.kwenza.context.Result;

import java.io.IOException;

/** {@code kwenza resolve}. See {@link ResolveCommand}. */
public final class ResolveCli implements CliCommand {

    @Override
    public String name() {
        return "resolve";
    }

    @Override
    public String about() {
        return "Write kwenza.lock if it is missing or out of date";
    }

    @Override
    public Result<Void> exec(Session session, Invocation invocation) throws IOException, InterruptedException {
        return session.resolveCommand().run();
    }
}
