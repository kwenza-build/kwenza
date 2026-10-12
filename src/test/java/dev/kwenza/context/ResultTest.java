package dev.kwenza.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResultTest {

    @Test
    void mapTransformsOkAndPassesErrThrough() {
        assertEquals(Result.ok(4), Result.ok(2).map(x -> x * 2));
        Result<Integer> err = Result.err("nope");
        assertEquals(err.failure(), err.map(x -> x * 2).failure());
    }

    @Test
    void flatMapStopsAtTheFirstErr() {
        Result<Integer> result = Result.ok(1)
                .flatMap(x -> Result.<Integer>err("first"))
                .flatMap(x -> Result.err("second"));

        assertEquals(List.of("first"), result.failure().problems());
    }

    @Test
    void allCollectsEveryProblemRatherThanJustTheFirst() {
        Result<List<Integer>> all = Result.all(List.of(Result.ok(1), Result.err("a"), Result.ok(3), Result.err("b")));

        assertEquals(List.of("a", "b"), all.failure().problems());
        assertEquals(Result.ok(List.of(1, 2)), Result.all(List.of(Result.ok(1), Result.ok(2))));
    }

    @Test
    void userUsageAndChildFailuresCarryTheirExitCodes() {
        assertEquals(1, Failure.user("x").exitCode());
        assertEquals(2, Failure.usage("x").exitCode());
        assertEquals(3, Failure.child(3).exitCode());
        assertEquals(List.of(), Failure.child(3).problems()); // the child already reported
    }

    @Test
    void getOnAnErrIsABugNotAUserError() {
        assertThrows(IllegalStateException.class, () -> Result.err("x").get());
    }

    @Test
    void failureOnAnOkIsABugToo() {
        assertThrows(IllegalStateException.class, () -> Result.ok(1).failure());
    }

    @Test
    void errChangesTypeWithoutLosingItsFailure() {
        Result<String> err = Result.err("x");
        Result<Integer> asInt = err.castErr();

        assertInstanceOf(Result.Err.class, asInt);
        assertEquals(err.failure(), asInt.failure());
    }
}
