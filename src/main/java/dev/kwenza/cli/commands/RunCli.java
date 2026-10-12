package dev.kwenza.cli.commands;

import dev.kwenza.cli.Session;
import dev.kwenza.commands.RunCommand;
import dev.kwenza.context.Result;

import java.io.IOException;

/** {@code kwenza run}. See {@link RunCommand}. */
public final class RunCli implements CliCommand {

    @Override
    public String name() {
        return "run";
    }

    @Override
    public String about() {
        return "Run the project's main class";
    }

    @Override
    public boolean common() {
        return true;
    }

    @Override
    public Result<Void> exec(Session session, Invocation invocation) throws IOException, InterruptedException {
        return new RunCommand(session.project(), session.buildCommand(), session.reporter()).run();
    }
}
