package dev.kwenza.cli;

import java.io.Console;
import java.lang.reflect.Method;

/**
 * Every colour decision kwenza's console output makes, in one place. {@link #PLAIN} never emits
 * escape codes; {@link #detect()} only colours when output really is a terminal and
 * <a href="https://no-color.org/">NO_COLOR</a> isn't set.
 */
public final class Style {
    private static final char ESC = 27;
    private static final String RESET = ESC + "[0m";
    private static final String BOLD = ESC + "[1m";
    // kwenza's brand ochre (#B87C0C / #EDB341 on the site), as the 256-colour palette's nearest
    // match that reads on both light and dark terminal backgrounds.
    private static final String OCHRE = ESC + "[38;5;172m";
    // Its partner on the site: the soft blue kwenza.dev's code highlighting pairs with the ochre
    // (#8FB8E0, --k-hl-type), nearest 256-colour match. Plays the part cyan does in Cargo's help.
    private static final String BLUE = ESC + "[38;5;110m";
    private static final String CYAN = ESC + "[36m";
    private static final String RED = ESC + "[31m";
    private static final String GRAY = ESC + "[90m";

    public static final Style PLAIN = new Style(false);
    /** Always colours, terminal or not; for tests. */
    public static final Style ANSI = new Style(true);

    private final boolean enabled;

    private Style(boolean enabled) {
        this.enabled = enabled;
    }

    public static Style detect() {
        return new Style(isTerminal() && System.getenv("NO_COLOR") == null);
    }

    /** Section headings in help output ({@code Usage:}, {@code Commands:}). */
    public String heading(String text) {
        return wrap(BOLD + OCHRE, text);
    }

    /** Things the user would type: command and option names. */
    public String literal(String text) {
        return wrap(BOLD + BLUE, text);
    }

    /** Stand-ins for something the user fills in: {@code <COMMAND>}, {@code [OPTIONS]}. */
    public String placeholder(String text) {
        return wrap(BLUE, text);
    }

    /** kwenza's own progress lines. */
    public String info(String text) {
        return wrap(CYAN, text);
    }

    public String error(String text) {
        return wrap(RED, text);
    }

    /** --verbose detail, dimmer than {@link #info}. */
    public String detail(String text) {
        return wrap(GRAY, text);
    }

    private String wrap(String code, String text) {
        return enabled ? code + text + RESET : text;
    }

    /**
     * {@code System.console() != null} used to mean "a real terminal", but since JDK 22 it can
     * return a console even when output is redirected; {@code Console.isTerminal()} (also JDK 22)
     * is the real check. Looked up reflectively because kwenza still runs on JDK 21.
     */
    private static boolean isTerminal() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            Method isTerminal = Console.class.getMethod("isTerminal");
            return (boolean) isTerminal.invoke(console);
        } catch (ReflectiveOperationException e) {
            return true; // JDK 21: a non-null console already means a terminal
        }
    }
}
