package dev.kwenza.cli.commands;

import dev.kwenza.cli.Session;
import dev.kwenza.commands.BuildCommand;
import dev.kwenza.context.Result;

import java.io.IOException;

/** {@code kwenza build}. See {@link BuildCommand}. */
public final class BuildCli implements CliCommand {

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String about() {
        return "Compile the current project";
    }

    @Override
    public boolean common() {
        return true;
    }

    @Override
    public Result<Void> exec(Session session, Invocation invocation) throws IOException, InterruptedException {
        return new BuildCommand(session.compile()).run();
    }
}
