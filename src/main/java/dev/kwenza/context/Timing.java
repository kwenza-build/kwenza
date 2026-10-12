package dev.kwenza.context;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight phase-timing diagnostics, enabled via KWENZA_TIMING=1.
 * Distinguishes JVM bootstrap (process start to main() entry, which a shell-level
 * wall-clock timer can't see) from time spent in each build phase.
 */
public final class Timing {
    private static final boolean ENABLED = System.getenv("KWENZA_TIMING") != null;

    private static final long JVM_START_EPOCH_MILLIS = ManagementFactory.getRuntimeMXBean().getStartTime();
    private static final long MAIN_ENTRY_EPOCH_MILLIS = System.currentTimeMillis();
    private static final long MAIN_ENTRY_NANOS = System.nanoTime();

    private static final List<String> labels = new ArrayList<>();
    private static final List<Long> marks = new ArrayList<>();

    private Timing() {
    }

    /** No-op body - calling this forces the class to load (and its static fields to capture "now") at this call site. */
    public static void init() {
    }

    public static void mark(String label) {
        if (!ENABLED) return;
        labels.add(label);
        marks.add(System.nanoTime());
    }

    public static void report() {
        if (!ENABLED) return;
        long jvmBootstrapMs = MAIN_ENTRY_EPOCH_MILLIS - JVM_START_EPOCH_MILLIS;
        System.err.printf("[timing] jvm-bootstrap-to-main: %dms%n", jvmBootstrapMs);

        long prev = MAIN_ENTRY_NANOS;
        String prevLabel = "main-entry";
        for (int i = 0; i < labels.size(); i++) {
            long deltaMs = (marks.get(i) - prev) / 1_000_000;
            System.err.printf("[timing] %s -> %s: %dms%n", prevLabel, labels.get(i), deltaMs);
            prev = marks.get(i);
            prevLabel = labels.get(i);
        }
        long totalMs = (prev - MAIN_ENTRY_NANOS) / 1_000_000;
        System.err.printf("[timing] main-entry -> %s (total): %dms%n", prevLabel, totalMs);
    }
}
