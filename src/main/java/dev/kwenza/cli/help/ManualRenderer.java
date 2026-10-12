package dev.kwenza.cli.help;

import dev.kwenza.cli.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a command's AsciiDoc manual page as plain terminal text, laid out like a man page
 * (section names flush left, body indented). Handles exactly the subset of AsciiDoc the pages
 * under {@code docs/modules/ROOT/pages/commands/} use, nothing more, which keeps it small enough
 * to hand-roll instead of depending on Asciidoctor:
 *
 * <ul>
 *   <li>the document title ({@code = kwenza-build(1)}), sections ({@code ==}) and subsections ({@code ===});
 *   <li>paragraphs, reflowed to {@value #WIDTH} columns;
 *   <li>description lists ({@code `--verbose`::}, one or more terms, then one paragraph);
 *   <li>bulleted lists ({@code * item});
 *   <li>listing blocks ({@code ----}), printed verbatim;
 *   <li>{@code NOTE:} paragraphs;
 *   <li>inline {@code `code`} (bold, when the {@link Style} has colour), {@code *bold*}, {@code _emphasis_}, {@code xref:}, {@code link:},
 *       URL macros and {@code <<anchor,text>>}.
 * </ul>
 *
 * Attribute entries, block attributes ({@code [source,bash]}), anchors and {@code //} comments
 * are dropped. Anything else would come out as raw markup, which {@code CliDocsTest} checks for
 * on every bundled page, so a page that strays outside this subset fails the build.
 */
public final class ManualRenderer {
    static final int WIDTH = 80;
    private static final String BODY = " ".repeat(7);
    private static final String NESTED = " ".repeat(11);

    private static final Pattern XREF = Pattern.compile("xref:([^\\[\\s]+)\\[([^\\]]*)]");
    private static final Pattern LINK = Pattern.compile("link:([^\\[\\s]+)\\[([^\\]]*)]");
    private static final Pattern URL_MACRO = Pattern.compile("(https?://[^\\[\\s]+)\\[([^\\]]*)]");
    private static final Pattern CROSS_REF = Pattern.compile("<<([^,>]+)(?:,\\s*([^>]+))?>>");
    private static final Pattern CODE = Pattern.compile("`\\+?([^`]+?)\\+?`");
    private static final Pattern BOLD = Pattern.compile("(?<![\\w*])\\*([^*\\s][^*]*?)\\*(?![\\w*])");
    private static final Pattern EMPHASIS = Pattern.compile("(?<![\\w_])_([^_\\s][^_]*?)_(?![\\w_])");
    // Mark inline code while reflowing, so the marks don't count towards a line's width; the last
    // step turns them into bold (or nothing, with colour off).
    private static final char CODE_START = '\u0001';
    private static final char CODE_END = '\u0002';
    private static final Pattern CODE_SPAN = Pattern.compile(CODE_START + "([^" + CODE_END + "]*)" + CODE_END);
    private static final Pattern DLIST_TERM = Pattern.compile("^(\\S.*?)::$");

    private final Style style;

    public ManualRenderer(Style style) {
        this.style = style;
    }

    public String render(String adoc) {
        String[] lines = adoc.split("\\R", -1);
        StringBuilder out = new StringBuilder();
        List<String> paragraph = new ArrayList<>();
        String indent = BODY;          // where the paragraph being collected will be printed
        String firstPrefix = "";       // e.g. "- " for a list item, "Note: " for a NOTE
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.strip();
            boolean blockBoundary = trimmed.isEmpty() || trimmed.equals("----") || line.startsWith("=")
                    || DLIST_TERM.matcher(trimmed).matches() || trimmed.startsWith("* ");
            if (blockBoundary && !paragraph.isEmpty()) {
                emitParagraph(out, paragraph, indent, firstPrefix);
                paragraph.clear();
                firstPrefix = "";
            }

            if (trimmed.isEmpty()) {
                indent = BODY; // a description list's description is one paragraph only
            } else if (paragraph.isEmpty()
                    && (trimmed.startsWith("//") || isAttributeEntry(trimmed) || isBlockAttribute(trimmed))) {
                // dropped
            } else if (trimmed.equals("----")) {
                i++;
                while (i < lines.length && !lines[i].strip().equals("----")) {
                    out.append(lines[i].isBlank() ? "" : indent + "    " + lines[i]).append('\n');
                    i++;
                }
                out.append('\n');
            } else if (line.startsWith("= ")) {
                out.append(style.heading(line.substring(2).strip().toUpperCase())).append("\n\n");
            } else if (line.startsWith("=== ")) {
                out.append("   ").append(style.heading(unmarked(inline(line.substring(4).strip())))).append('\n');
            } else if (line.startsWith("== ")) {
                out.append(style.heading(unmarked(inline(line.substring(3).strip())).toUpperCase())).append('\n');
            } else if (DLIST_TERM.matcher(trimmed).matches()) {
                List<String> terms = new ArrayList<>();
                while (i < lines.length && DLIST_TERM.matcher(lines[i].strip()).matches()) {
                    Matcher m = DLIST_TERM.matcher(lines[i].strip());
                    m.matches();
                    terms.add(inline(m.group(1)));
                    i++;
                }
                out.append(BODY).append(String.join(", ", terms)).append('\n');
                indent = NESTED;
                continue;
            } else if (trimmed.startsWith("* ")) {
                paragraph.add(trimmed.substring(2));
                firstPrefix = "- ";
            } else if (paragraph.isEmpty() && trimmed.startsWith("NOTE: ")) {
                paragraph.add(trimmed.substring("NOTE: ".length()));
                firstPrefix = "Note: ";
            } else {
                paragraph.add(trimmed);
            }
            i++;
        }
        if (!paragraph.isEmpty()) {
            emitParagraph(out, paragraph, indent, firstPrefix);
        }
        return finish(out.toString());
    }

    /**
     * Turns the code marks into {@link Style#literal} spans, one line at a time: a span that
     * reflowing split across lines is closed at the end of each line and reopened after the next
     * one's indent, so no escape code ever runs across a line break.
     */
    private String finish(String marked) {
        StringBuilder result = new StringBuilder();
        boolean open = false;
        for (String line : marked.split("\n", -1)) {
            if (open) {
                int indent = line.length() - line.stripLeading().length();
                line = line.substring(0, indent) + CODE_START + line.substring(indent);
            }
            open = line.lastIndexOf(CODE_START) > line.lastIndexOf(CODE_END);
            if (open) {
                line = line + CODE_END;
            }
            result.append(replace(CODE_SPAN, line, m -> style.literal(m.group(1)))).append('\n');
        }
        return result.toString().stripTrailing() + "\n";
    }

    /** Reflows one paragraph to {@link #WIDTH}; continuation lines line up under the text, past any prefix. */
    private static void emitParagraph(StringBuilder out, List<String> lines, String indent, String firstPrefix) {
        String text = inline(String.join(" ", lines));
        String hanging = indent + " ".repeat(firstPrefix.length());
        StringBuilder current = new StringBuilder(indent).append(firstPrefix);
        int startLength = current.length();
        for (String word : text.split("\\s+")) {
            if (current.length() > startLength && visibleLength(current) + 1 + visibleLength(word) > WIDTH) {
                out.append(current).append('\n');
                current = new StringBuilder(hanging);
                startLength = current.length();
            }
            if (current.length() > startLength) {
                current.append(' ');
            }
            current.append(word);
        }
        out.append(current).append("\n\n");
    }

    private static int visibleLength(CharSequence text) {
        int marks = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == CODE_START || text.charAt(i) == CODE_END) {
                marks++;
            }
        }
        return text.length() - marks;
    }

    /** Strips inline markup down to the text a reader should see, with code spans marked for {@link #finish}. */
    static String inline(String text) {
        text = replace(XREF, text, m -> !m.group(2).isEmpty() ? m.group(2) : pageName(m.group(1)));
        text = replace(LINK, text, m -> !m.group(2).isEmpty() ? m.group(2) : m.group(1));
        text = replace(URL_MACRO, text, m -> m.group(2).isEmpty() ? m.group(1) : m.group(2) + " (" + m.group(1) + ")");
        text = replace(CROSS_REF, text, m -> m.group(2) != null ? m.group(2) : m.group(1));
        text = replace(CODE, text, m -> CODE_START + m.group(1) + CODE_END);
        text = replace(BOLD, text, m -> m.group(1));
        text = replace(EMPHASIS, text, m -> m.group(1));
        return text;
    }

    private static String unmarked(String text) {
        return text.replace(String.valueOf(CODE_START), "").replace(String.valueOf(CODE_END), "");
    }

    /** {@code ./kwenza-run.adoc#x} as man pages name it, {@code kwenza-run(1)}; any other page by its file name. */
    private static String pageName(String target) {
        String file = target.replaceFirst("#.*$", "");
        String stem = file.substring(file.lastIndexOf('/') + 1).replaceFirst("\\.adoc$", "");
        return stem.startsWith("kwenza") ? stem + "(1)" : stem;
    }

    private static String replace(Pattern pattern, String text, java.util.function.Function<Matcher, String> replacement) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean isAttributeEntry(String line) {
        return line.matches(":!?[\\w-]+!?:.*");
    }

    private static boolean isBlockAttribute(String line) {
        return line.startsWith("[") && line.endsWith("]");
    }
}
