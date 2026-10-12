package dev.kwenza.cli.commands;

import dev.kwenza.cli.Session;
import dev.kwenza.context.Result;

/**
 * {@code kwenza help [command]}: with a command, prints its full manual page (the same page
 * kwenza.dev publishes); without one, the same usage as a bare {@code kwenza}.
 */
public final class HelpCli implements CliCommand {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String about() {
        return "Display help for a kwenza command";
    }

    @Override
    public String argumentName() {
        return "command";
    }

    @Override
    public boolean needsProject() {
        return false;
    }

    @Override
    public Result<Void> exec(Session session, Invocation invocation) {
        if (invocation.argument() == null) {
            session.help().printUsage();
            return Result.ok();
        }
        return session.help().printManual(invocation.argument());
    }
}
