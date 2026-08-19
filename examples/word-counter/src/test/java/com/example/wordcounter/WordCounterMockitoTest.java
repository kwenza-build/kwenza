package com.example.wordcounter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WordCounterMockitoTest {

    @Test
    void delegatesEachWordToTheFilter() {
        WordFilter filter = mock(WordFilter.class);
        when(filter.isValid("kwenza")).thenReturn(true);
        when(filter.isValid("x")).thenReturn(false);

        WordCounter counter = new WordCounter(filter);
        long count = counter.countValidWords("kwenza x");

        assertEquals(1, count);
        verify(filter).isValid("kwenza");
        verify(filter).isValid("x");
    }
}
