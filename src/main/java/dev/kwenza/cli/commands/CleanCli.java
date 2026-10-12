package dev.kwenza.cli.commands;

import dev.kwenza.cli.Session;
import dev.kwenza.commands.CleanCommand;
import dev.kwenza.context.Result;

import java.io.IOException;

/** {@code kwenza clean}. See {@link CleanCommand}. */
public final class CleanCli implements CliCommand {

    @Override
    public String name() {
        return "clean";
    }

    @Override
    public String about() {
        return "Remove the output directory";
    }

    @Override
    public boolean common() {
        return true;
    }

    @Override
    public Result<Void> exec(Session session, Invocation invocation) throws IOException, InterruptedException {
        return new CleanCommand(session.project(), session.compile(), session.reporter()).run();
    }
}
