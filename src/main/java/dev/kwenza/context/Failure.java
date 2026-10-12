package dev.kwenza.context;

import java.util.ArrayList;
import java.util.List;

/**
 * Why an operation didn't succeed, as a value: the exit code kwenza should end with, and the
 * problems to tell the user (each one specific and actionable). Several problems at once is how
 * one run reports, say, every checksum mismatch instead of only the first.
 *
 * @param exitCode 1 for anything the user can fix, 2 for a bad command line, or a child process's
 *                 own exit code passed through
 * @param problems what to print, one {@code error:} line each; empty when the failure has already
 *                 been reported (a child process printed its own output)
 */
public record Failure(int exitCode, List<String> problems) {
    public static final int USER_ERROR = 1;
    public static final int USAGE_ERROR = 2;

    public Failure {
        problems = List.copyOf(problems);
    }

    /** Something the user can fix: a bad manifest, a checksum mismatch, a failed compile. */
    public static Failure user(String problem) {
        return new Failure(USER_ERROR, List.of(problem));
    }

    /** A command line kwenza can't make sense of. */
    public static Failure usage(String problem) {
        return new Failure(USAGE_ERROR, List.of(problem));
    }

    /** A child process (`run`, `test`) exited non-zero and has already said why. */
    public static Failure child(int exitCode) {
        return new Failure(exitCode, List.of());
    }

    /** Both failures' problems, keeping this one's exit code. */
    public Failure plus(Failure other) {
        List<String> all = new ArrayList<>(problems);
        all.addAll(other.problems);
        return new Failure(exitCode, all);
    }
}
