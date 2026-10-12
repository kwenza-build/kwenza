package dev.kwenza.cli.commands;

import dev.kwenza.cli.Session;
import dev.kwenza.commands.PackageCommand;

/** {@code kwenza package}. See {@link PackageCommand}. */
public final class PackageCli implements CliCommand {

    @Override
    public String name() {
        return "package";
    }

    @Override
    public String about() {
        return "Package the project as a jar";
    }

    @Override
    public boolean common() {
        return true;
    }

    @Override
    public void exec(Session session, Invocation invocation) throws Exception {
        new PackageCommand(session.project(), session.compile(), session.reporter()).run();
    }
}
