package dev.kwenza.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TimingTest {
    private static final long MS = 1_000_000;

    private final AtomicLong now = new AtomicLong();

    private Timing.Recorder recorder(boolean enabled) {
        return new Timing.Recorder(enabled, now::get);
    }

    @Test
    void eachPhaseReportsHowLongItsOwnScopeTook() {
        Timing.Recorder recorder = recorder(true);
        now.set(2 * MS);
        try (var resolve = recorder.phase("resolve")) {
            now.addAndGet(10 * MS);
        }
        now.addAndGet(5 * MS); // between phases: not attributed to either
        try (var compile = recorder.phase("compile")) {
            now.addAndGet(20 * MS);
        }

        assertEquals(List.of(
                "[timing] jvm-bootstrap-to-main: 7ms",
                "[timing] resolve: 10ms",
                "[timing] compile: 20ms",
                "[timing] total (main to report): 37ms"), recorder.lines(7));
    }

    @Test
    void nestedPhasesAreIndentedUnderTheirParent() {
        Timing.Recorder recorder = recorder(true);
        try (var build = recorder.phase("build")) {
            try (var resolve = recorder.phase("resolve")) {
                now.addAndGet(3 * MS);
            }
            now.addAndGet(4 * MS);
        }

        assertEquals(List.of(
                "[timing] jvm-bootstrap-to-main: 0ms",
                "[timing] build: 7ms",
                "[timing]   resolve: 3ms",
                "[timing] total (main to report): 7ms"), recorder.lines(0));
    }

    @Test
    void disabledItRecordsNothingAndHandsOutOneSharedNoOpScope() {
        Timing.Recorder recorder = recorder(false);

        assertSame(recorder.phase("a"), recorder.phase("b"));
        assertEquals(List.of(), recorder.lines(5));
    }
}
