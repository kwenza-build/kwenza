package dev.kwenza.cli;

import dev.kwenza.cli.commands.CliCommand;
import dev.kwenza.cli.commands.CliOption;
import dev.kwenza.cli.help.Help;
import dev.kwenza.cli.help.ManualRenderer;
import dev.kwenza.context.Failure;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the CLI and its manual pages in step, since nothing else would: every command has a page
 * (published on kwenza.dev and bundled for {@code kwenza help}), every page belongs to a command,
 * each page's NAME line is the command's {@code about()}, its OPTIONS are exactly the options the
 * parser accepts, and it renders to plain text with no markup left over.
 *
 * <p>Reads the pages from {@code docs/} (tests run from the project root) as well as from the
 * classpath, which is what catches a page whose symlink under {@code src/main/resources} is missing.
 */
class CliDocsTest {
    private static final Path PAGES = Path.of("docs/modules/ROOT/pages/commands");
    private static final Pattern OPTION_TERM = Pattern.compile("^`(--?[\\w-]+)`(?: <[^>]+>)?::$");
    private static final List<String> LEFTOVER_MARKUP =
            List.of("xref:", "link:", "`", "::\n", "[[", "<<", "----", "|===", "include::", "ifdef::", "{");

    private static String page(String name) throws IOException {
        return Files.readString(PAGES.resolve(name + ".adoc"));
    }

    @Test
    void everyCommandHasABundledManualPageIdenticalToItsDocsPage() throws IOException {
        for (CliCommand command : Cli.COMMANDS) {
            assertEquals(page("kwenza-" + command.name()), Help.manualSource(command).get(),
                    "src/main/resources" + Help.MANUAL_DIR + "kwenza-" + command.name()
                            + ".adoc should be a symlink to " + PAGES + "/kwenza-" + command.name() + ".adoc");
        }
    }

    @Test
    void everyCommandPageBelongsToACommand() throws IOException {
        Set<String> expected = new TreeSet<>(List.of("index.adoc", "kwenza.adoc"));
        Cli.COMMANDS.forEach(c -> expected.add("kwenza-" + c.name() + ".adoc"));
        Set<String> actual = new TreeSet<>();
        try (Stream<Path> files = Files.list(PAGES)) {
            files.forEach(f -> actual.add(f.getFileName().toString()));
        }

        assertEquals(expected, actual);
    }

    @Test
    void eachPagesNameLineIsTheCommandsSummary() throws IOException {
        for (CliCommand command : Cli.COMMANDS) {
            String expected = "kwenza-" + command.name() + " - " + command.about();
            assertTrue(page("kwenza-" + command.name()).contains("== NAME\n\n" + expected + "\n"),
                    "kwenza-" + command.name() + ".adoc's NAME line should read: " + expected);
        }
    }

    @Test
    void eachPageDocumentsExactlyTheOptionsItsCommandAccepts() throws IOException {
        for (CliCommand command : Cli.COMMANDS) {
            assertEquals(optionNames(Cli.commandOptions(command)), documentedOptions(page("kwenza-" + command.name())),
                    "OPTIONS in kwenza-" + command.name() + ".adoc");
        }
        assertEquals(optionNames(Cli.TOP_LEVEL_OPTIONS), documentedOptions(page("kwenza")), "OPTIONS in kwenza.adoc");
    }

    @Test
    void everyPageDocumentsEveryExitCodeKwenzaItselfUses() throws IOException {
        List<String> pages = new ArrayList<>(List.of("kwenza"));
        Cli.COMMANDS.forEach(c -> pages.add("kwenza-" + c.name()));
        for (String name : pages) {
            String page = page(name);
            int start = page.indexOf("\n== EXIT STATUS\n");
            assertTrue(start >= 0, name + ".adoc has no EXIT STATUS section");
            int end = page.indexOf("\n== ", start + 1);
            String exitStatus = page.substring(start, end < 0 ? page.length() : end);
            for (int code : List.of(0, Failure.USER_ERROR, Failure.USAGE_ERROR, Cli.BUG_EXIT_CODE)) {
                assertTrue(exitStatus.contains("* `" + code + "`:"), name + ".adoc's EXIT STATUS doesn't list " + code);
            }
        }
    }

    @Test
    void everyBundledPageRendersWithNoMarkupLeftOver() {
        for (CliCommand command : Cli.COMMANDS) {
            String text = new ManualRenderer(Style.PLAIN).render(Help.manualSource(command).get());
            for (String markup : LEFTOVER_MARKUP) {
                assertFalse(text.contains(markup),
                        "kwenza help " + command.name() + " prints raw '" + markup.strip() + "':\n" + text);
            }
        }
    }

    private static Set<String> optionNames(List<CliOption> options) {
        Set<String> names = new TreeSet<>();
        for (CliOption option : options) {
            names.add(option.longName());
            if (option.shortName() != null) {
                names.add(option.shortName());
            }
        }
        return names;
    }

    /** Every option term in the page's OPTIONS section. */
    private static Set<String> documentedOptions(String page) {
        int start = page.indexOf("\n== OPTIONS\n");
        assertTrue(start >= 0, "no OPTIONS section");
        int end = page.indexOf("\n== ", start + 1);
        List<String> lines = new ArrayList<>(page.substring(start, end < 0 ? page.length() : end).lines().toList());
        Set<String> names = new TreeSet<>();
        for (String line : lines) {
            Matcher m = OPTION_TERM.matcher(line.strip());
            if (m.matches()) {
                names.add(m.group(1));
            }
        }
        return names;
    }
}
