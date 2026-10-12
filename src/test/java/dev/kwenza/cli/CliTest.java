package dev.kwenza.cli;

import dev.kwenza.cli.Cli.CommandLine;
import dev.kwenza.cli.commands.BuildCli;
import dev.kwenza.cli.commands.HelpCli;
import dev.kwenza.cli.commands.Invocation;
import dev.kwenza.cli.commands.RunCli;
import dev.kwenza.context.KwenzaException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {

    private static CommandLine parse(String... args) {
        return Cli.parse(args);
    }

    @Test
    void aChainParsesToOneInvocationPerCommandInOrder() {
        List<Invocation> invocations = parse("clean", "build", "run").invocations();

        assertEquals(List.of("clean", "build", "run"),
                invocations.stream().map(i -> i.command().name()).toList());
    }

    @Test
    void verboseIsGlobalWhereverItAppears() {
        assertTrue(parse("-v", "build").verbose());
        assertTrue(parse("build", "--verbose", "run").verbose());
        assertFalse(parse("build").verbose());
    }

    @Test
    void helpAfterACommandAsksForThatCommandsHelp() {
        CommandLine line = parse("build", "run", "--help");

        assertFalse(line.help());
        assertFalse(line.invocations().get(0).help());
        assertTrue(line.invocations().get(1).help());
        assertInstanceOf(RunCli.class, line.invocations().get(1).command());
    }

    @Test
    void helpBeforeAnyCommandAsksForTheUsage() {
        assertTrue(parse("--help").help());
        assertTrue(parse("-h").help());
    }

    @Test
    void versionAndListAreTopLevelOptions() {
        assertTrue(parse("--version").version());
        assertTrue(parse("-V").version());
        assertTrue(parse("--list").list());
    }

    @Test
    void versionAfterACommandIsAnUnknownOptionForThatCommand() {
        KwenzaException e = assertThrows(KwenzaException.class, () -> parse("build", "--version"));

        assertTrue(e.getMessage().contains("`kwenza build`"), e.getMessage());
    }

    @Test
    void anOptionNoCommandTakesFailsBeforeAnythingRuns() {
        KwenzaException e = assertThrows(KwenzaException.class, () -> parse("clean", "--update"));

        assertTrue(e.getMessage().contains("'--update'"), e.getMessage());
    }

    @Test
    void anUnknownCommandAnywhereInTheChainFailsWithAPointerToList() {
        KwenzaException e = assertThrows(KwenzaException.class, () -> parse("build", "biuld"));

        assertTrue(e.getMessage().contains("no such command: `biuld`"), e.getMessage());
        assertTrue(e.getMessage().contains("kwenza --list"), e.getMessage());
    }

    @Test
    void helpTakesTheNextWordAsItsArgumentNotAsACommand() {
        CommandLine line = parse("help", "build");

        assertEquals(1, line.invocations().size());
        assertInstanceOf(HelpCli.class, line.invocations().get(0).command());
        assertEquals("build", line.invocations().get(0).argument());
    }

    @Test
    void aCommandWithoutAnArgumentTreatsTheNextWordAsTheNextCommand() {
        CommandLine line = parse("build", "run");

        assertNull(line.invocations().get(0).argument());
        assertInstanceOf(BuildCli.class, line.invocations().get(0).command());
    }

    @Test
    void noArgumentsMeansNoInvocations() {
        assertTrue(parse().invocations().isEmpty());
    }

    @Test
    void runPrintsUsageAndSucceedsWithNoArguments() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = new Cli(new PrintStream(out), new PrintStream(new ByteArrayOutputStream()), Style.PLAIN)
                .run(new String[0]);

        assertEquals(0, exitCode);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Usage: kwenza"));
    }

    @Test
    void runReportsAParseErrorOnStderrAndExitsOne() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = new Cli(new PrintStream(new ByteArrayOutputStream()), new PrintStream(err), Style.PLAIN)
                .run(new String[]{"biuld"});

        assertEquals(1, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).startsWith("error: no such command"));
    }

    @Test
    void commandHelpRunsNothing() {
        // `build --help` outside any project: if it tried to build, it would fail on the missing
        // kwenza.toml instead of printing help.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = new Cli(new PrintStream(out), new PrintStream(new ByteArrayOutputStream()), Style.PLAIN)
                .run(new String[]{"build", "--help"});

        assertEquals(0, exitCode);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Usage: kwenza build"));
    }
}
