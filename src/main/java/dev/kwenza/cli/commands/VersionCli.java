package dev.kwenza.cli.commands;

import dev.kwenza.cli.Session;

/** {@code kwenza version}, also spelled {@code kwenza --version} / {@code -V}. */
public final class VersionCli implements CliCommand {

    @Override
    public String name() {
        return "version";
    }

    @Override
    public String about() {
        return "Show version information";
    }

    @Override
    public void exec(Session session, Invocation invocation) {
        session.help().printVersion();
    }
}
