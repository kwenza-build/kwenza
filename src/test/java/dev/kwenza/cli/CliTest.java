package dev.kwenza.cli;

import dev.kwenza.cli.Cli.CommandLine;
import dev.kwenza.cli.commands.BuildCli;
import dev.kwenza.cli.commands.HelpCli;
import dev.kwenza.cli.commands.Invocation;
import dev.kwenza.cli.commands.RunCli;
import dev.kwenza.cli.commands.CliCommand;
import dev.kwenza.cli.help.Help;
import dev.kwenza.context.Failure;
import dev.kwenza.context.GlobalContext;
import dev.kwenza.context.Reporter;
import dev.kwenza.context.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CliTest {

    private static CommandLine parse(String... args) {
        return Cli.parse(args).get();
    }

    /** The single problem a command line that shouldn't parse fails with, checking it's a usage error. */
    private static String usageProblem(String... args) {
        Failure failure = Cli.parse(args).failure();
        assertEquals(Failure.USAGE_ERROR, failure.exitCode());
        return failure.problems().get(0);
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
        String problem = usageProblem("build", "--version");

        assertTrue(problem.contains("`kwenza build`"), problem);
    }

    @Test
    void anOptionNoCommandTakesFailsBeforeAnythingRuns() {
        String problem = usageProblem("clean", "--update");

        assertTrue(problem.contains("'--update'"), problem);
    }

    @Test
    void anUnknownCommandAnywhereInTheChainFailsWithAPointerToList() {
        String problem = usageProblem("build", "biuld");

        assertTrue(problem.contains("no such command: `biuld`"), problem);
        assertTrue(problem.contains("kwenza --list"), problem);
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
    void runReportsAParseErrorOnStderrAndExitsTwo() {
        Run run = run(Cli.COMMANDS, "biuld");

        assertEquals(2, run.exitCode);
        assertTrue(run.err.startsWith("error: no such command"), run.err);
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

    // A command that does nothing but return (or throw) what it's given, for the exit-code tests.
    private record Fixed(String name, Callable<Result<Void>> outcome) implements CliCommand {
        @Override
        public String about() {
            return "test fixture";
        }

        @Override
        public boolean needsProject() {
            return false;
        }

        @Override
        public Result<Void> exec(Session session, Invocation invocation) {
            try {
                return outcome.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    private record Run(int exitCode, String out, String err) {
    }

    private static Run run(List<CliCommand> commands, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = new Cli(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Style.PLAIN, commands).run(args);
        return new Run(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aUserFixableFailurePrintsEveryProblemAndExitsOne() {
        Run run = run(List.of(new Fixed("fail", () -> Result.err(new Failure(1, List.of("first", "second"))))), "fail");

        assertEquals(1, run.exitCode);
        assertEquals("error: first\nerror: second\n", run.err);
    }

    @Test
    void aChildProcessesExitCodePassesThroughSilently() {
        Run run = run(List.of(new Fixed("child", () -> Result.err(Failure.child(3)))), "child");

        assertEquals(3, run.exitCode);
        assertEquals("", run.err);
    }

    @Test
    void aFailureStopsTheChainBeforeTheNextCommand() {
        AtomicBoolean secondRan = new AtomicBoolean();
        Run run = run(List.of(
                new Fixed("fail", () -> Result.err("nope")),
                new Fixed("next", () -> {
                    secondRan.set(true);
                    return Result.ok();
                })), "fail", "next");

        assertEquals(1, run.exitCode);
        assertFalse(secondRan.get());
    }

    @Test
    void anExceptionIsReportedAsAKwenzaBugWithExit101() {
        Run run = run(List.of(new Fixed("boom", () -> {
            throw new IllegalStateException("broken invariant");
        })), "boom");

        assertEquals(101, run.exitCode);
        assertTrue(run.err.contains("internal error: java.lang.IllegalStateException: broken invariant"), run.err);
        assertTrue(run.err.contains("this is a bug in kwenza"), run.err);
        assertFalse(run.err.contains("\tat "), "no stack trace without --verbose:\n" + run.err);
    }

    @Test
    void aBugsStackTraceIsShownUnderVerbose() {
        Run run = run(List.of(new Fixed("boom", () -> {
            throw new IllegalStateException("broken invariant");
        })), "boom", "--verbose");

        assertTrue(run.err.contains("\tat "), run.err);
    }

    @Test
    void aCommandThatNeedsAProjectFailsCleanlyWithoutOne(@TempDir Path emptyDir) {
        // Run from the real working directory would find kwenza's own kwenza.toml, so check the
        // project loading through a Session pointed at an empty directory instead.
        Session session = new Session(new GlobalContext(emptyDir, emptyDir, mock(Reporter.class)),
                new Help(new PrintStream(new ByteArrayOutputStream()), Style.PLAIN));

        assertThrows(IllegalStateException.class, session::project);
        String problem = assertDoesNotThrow(session::loadProject).failure().problems().get(0);
        assertTrue(problem.startsWith("No kwenza.toml found in "), problem);
    }
}
