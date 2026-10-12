package dev.kwenza.cli.help;

import dev.kwenza.cli.Style;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualRendererTest {

    private static String render(String adoc) {
        return new ManualRenderer(Style.PLAIN).render(adoc);
    }

    @Test
    void titleAndSectionsBecomeManPageHeadingsWithTheBodyIndented() {
        String text = render("""
                = kwenza-x(1)
                :page-aliases: old.adoc

                == NAME

                kwenza-x - Do a thing
                """);

        assertEquals("KWENZA-X(1)\n\nNAME\n       kwenza-x - Do a thing\n", text);
    }

    @Test
    void descriptionListTermsJoinOnOneLineWithTheDescriptionIndentedUnderneath() {
        String text = render("""
                `-v`::
                `--verbose`::
                Print more.
                """);

        assertEquals("       -v, --verbose\n           Print more.\n", text);
    }

    @Test
    void listingBlocksStayVerbatim() {
        String text = render("""
                [source,bash]
                ----
                kwenza  build   *not bold*
                ----
                """);

        assertEquals("           kwenza  build   *not bold*\n", text);
    }

    @Test
    void inlineMarkupIsReducedToTheTextAReaderShouldSee() {
        assertEquals("kwenza-run(1)", ManualRenderer.inline("xref:./kwenza-run.adoc[]"));
        assertEquals("Manifest Reference", ManualRenderer.inline("xref:manifest-reference.adoc[Manifest Reference]"));
        assertEquals("no-color.org (https://no-color.org/)", ManualRenderer.inline("https://no-color.org/[no-color.org]"));
        assertEquals("[options] and snake_case_name", ManualRenderer.inline("[_options_] and snake_case_name"));
    }

    @Test
    void codeIsPlainWithoutColourAndBoldWithIt() {
        assertEquals("       run kwenza build now\n", render("run `kwenza build` *now*"));
        assertEquals("       run " + Style.ANSI.literal("kwenza build") + " now\n",
                new ManualRenderer(Style.ANSI).render("run `kwenza build` *now*"));
    }

    @Test
    void aCodeSpanSplitAcrossLinesIsClosedAndReopenedOnEachLine() {
        String text = new ManualRenderer(Style.ANSI).render("x".repeat(66) + " `one two three four`");

        for (String line : text.split("\n")) {
            assertTrue(line.isEmpty() || line.endsWith("\u001b[0m") || !line.contains("\u001b"), line);
        }
    }

    @Test
    void paragraphsReflowToTheWidth() {
        String word = "abcdefghi ";
        String text = render(word.repeat(20));

        for (String line : text.split("\n")) {
            assertTrue(line.length() <= ManualRenderer.WIDTH, line);
        }
        assertTrue(text.lines().count() > 1);
    }

    @Test
    void bulletsAndNotesKeepAHangingIndent() {
        assertEquals("       - one\n\n       Note: two\n", render("* one\n\nNOTE: two\n"));
    }
}
