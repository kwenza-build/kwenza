package dev.kwenza.cli.help;

import dev.kwenza.cli.Cli;
import dev.kwenza.cli.Style;
import dev.kwenza.cli.commands.CliCommand;
import dev.kwenza.context.KwenzaException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpTest {

    private static String print(Consumer<Help> printer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        printer.accept(new Help(new PrintStream(out, true, StandardCharsets.UTF_8), Style.PLAIN));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void usageListsTheCommonCommandsOnly() {
        String usage = print(Help::printUsage);

        assertTrue(usage.contains("  build "), usage);
        assertFalse(usage.contains("  resolve "), usage);
        assertTrue(usage.contains("--list"), usage);
    }

    @Test
    void listShowsEveryCommandWithItsSummary() {
        String list = print(Help::printList);

        for (CliCommand command : Cli.COMMANDS) {
            assertTrue(list.contains(command.name()) && list.contains(command.about()), list);
        }
    }

    @Test
    void commandHelpShowsTheGlobalOptionsAndPointsAtTheManual() {
        String help = print(h -> h.printCommandHelp(Cli.find("test")));

        assertTrue(help.contains("-v, --verbose"), help);
        assertTrue(help.contains("kwenza help test"), help);
    }

    @Test
    void manualIsTheRenderedPageForThatCommand() {
        String manual = print(h -> h.printManual("build"));

        assertTrue(manual.startsWith("KWENZA-BUILD(1)"), manual);
        assertTrue(manual.contains("kwenza-build - Compile the current project"), manual);
    }

    @Test
    void manualForAnUnknownCommandFailsLikeAnUnknownCommand() {
        assertThrows(KwenzaException.class, () -> print(h -> h.printManual("biuld")));
    }

    @Test
    void plainStyleNeverEmitsEscapeCodes() {
        String usage = print(Help::printUsage);

        assertFalse(usage.contains("\u001b"), usage);
    }
}
