package dev.kwenza.cli.commands;

/**
 * One command-line option, as both the parser and the help output see it.
 *
 * @param longName  e.g. {@code --verbose}
 * @param shortName e.g. {@code -v}, or null
 * @param valueName the placeholder for an option that takes a value (e.g. {@code dir} for
 *                  {@code --reports-dir <dir>}), or null for a plain flag
 * @param help      one line for {@code --help}; must match the option's entry in the command's
 *                  manual page (enforced by a test)
 */
public record CliOption(String longName, String shortName, String valueName, String help) {

    public static CliOption flag(String longName, String shortName, String help) {
        return new CliOption(longName, shortName, null, help);
    }

    public boolean takesValue() {
        return valueName != null;
    }

    public boolean matches(String name) {
        return name.equals(longName) || name.equals(shortName);
    }

    /** {@code -v, --verbose} or {@code --reports-dir <dir>}, as help output shows it. */
    public String display() {
        String names = shortName != null ? shortName + ", " + longName : longName;
        return takesValue() ? names + " <" + valueName + ">" : names;
    }
}
