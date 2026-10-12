package dev.kwenza.context;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Phase-timing diagnostics, enabled with {@code KWENZA_TIMING=1}. Each phase is a scope:
 * <pre>{@code
 * try (var t = Timing.phase("compile")) {
 *     ...
 * }
 * }</pre>
 * so it has an explicit start and end, nests, and simply doesn't appear when a memoized command
 * skips it. The whole run is a scope too, {@link #start()}, opened as main()'s first statement and
 * printing the report when it closes. Also reports JVM bootstrap (process start to main() entry), which a shell-level
 * wall-clock timer can't separate out. Disabled, {@link #phase} returns one shared no-op scope.
 */
public final class Timing {
    private static final long JVM_START_EPOCH_MILLIS = ManagementFactory.getRuntimeMXBean().getStartTime();
    private static final long MAIN_ENTRY_EPOCH_MILLIS = System.currentTimeMillis();
    private static final Recorder RECORDER = new Recorder(System.getenv("KWENZA_TIMING") != null, System::nanoTime);

    private Timing() {
    }

    /** One timed phase; closing it ends it. Never throws. */
    public interface Phase extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * The whole run, as main()'s first statement: loading this class here is what captures the
     * true main() entry time. Closing it prints every finished phase to stderr, if enabled.
     */
    public static Phase start() {
        return Timing::report;
    }

    public static Phase phase(String name) {
        return RECORDER.phase(name);
    }

    private static void report() {
        for (String line : RECORDER.lines(MAIN_ENTRY_EPOCH_MILLIS - JVM_START_EPOCH_MILLIS)) {
            System.err.println(line);
        }
    }

    /** The recording itself, with an injectable clock so it's testable. */
    static final class Recorder {
        private static final Phase NO_OP = () -> {
        };

        private record Finished(String name, int depth, long start, long end) {
        }

        private final boolean enabled;
        private final LongSupplier nanos;
        private final long origin;
        private final List<Finished> finished = new ArrayList<>();
        private int depth;

        Recorder(boolean enabled, LongSupplier nanos) {
            this.enabled = enabled;
            this.nanos = nanos;
            this.origin = nanos.getAsLong();
        }

        Phase phase(String name) {
            if (!enabled) {
                return NO_OP;
            }
            long start = nanos.getAsLong();
            int level = depth++;
            int slot = finished.size();
            finished.add(null); // keeps phases in the order they started, parents before children
            return () -> {
                depth--;
                finished.set(slot, new Finished(name, level, start, nanos.getAsLong()));
            };
        }

        List<String> lines(long jvmBootstrapMillis) {
            if (!enabled) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            lines.add("[timing] jvm-bootstrap-to-main: " + jvmBootstrapMillis + "ms");
            for (Finished phase : finished) {
                if (phase != null) {
                    lines.add("[timing] " + "  ".repeat(phase.depth()) + phase.name() + ": "
                            + (phase.end() - phase.start()) / 1_000_000 + "ms");
                }
            }
            lines.add("[timing] total (main to report): " + (nanos.getAsLong() - origin) / 1_000_000 + "ms");
            return lines;
        }
    }
}
