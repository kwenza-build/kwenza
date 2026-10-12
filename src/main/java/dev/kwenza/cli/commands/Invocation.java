package dev.kwenza.cli.commands;

import java.util.Map;

/**
 * One command from the command line, with whatever was given after it: {@code build},
 * {@code help run}, and so on. A chain like {@code kwenza clean build run} parses to three.
 *
 * @param command  which command
 * @param options  its own options (never the global ones), mapped to their value ({@code ""} for a flag)
 * @param argument its positional argument, if it takes one and one was given, else null
 * @param help     {@code -h}/{@code --help} was given after it: show its help instead of running it
 */
public record Invocation(CliCommand command, Map<CliOption, String> options, String argument, boolean help) {

    public boolean has(CliOption option) {
        return options.containsKey(option);
    }

    public String value(CliOption option) {
        return options.get(option);
    }
}
