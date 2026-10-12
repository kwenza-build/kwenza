package dev.kwenza.cli.commands;

import dev.kwenza.cli.Session;
import dev.kwenza.commands.RunCommand;

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
    public void exec(Session session, Invocation invocation) throws Exception {
        new RunCommand(session.project(), session.compile(), session.reporter()).run();
    }
}
