package dev.kwenza.cli.commands;

import dev.kwenza.cli.Session;
import dev.kwenza.commands.TestCommand;

/** {@code kwenza test}. See {@link TestCommand}. */
public final class TestCli implements CliCommand {

    @Override
    public String name() {
        return "test";
    }

    @Override
    public String about() {
        return "Compile and run the tests";
    }

    @Override
    public boolean common() {
        return true;
    }

    @Override
    public void exec(Session session, Invocation invocation) throws Exception {
        new TestCommand(session.project(), session.compile(), session.reporter()).run();
    }
}
