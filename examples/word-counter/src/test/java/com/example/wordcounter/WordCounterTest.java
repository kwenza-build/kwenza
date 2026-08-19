package com.example.wordcounter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WordCounterTest {

    @Test
    void countsOnlyWordsLongerThanFourLetters() {
        WordCounter counter = new WordCounter(word -> word.length() > 4);

        assertEquals(2, counter.countValidWords("kwenza builds java fast"));
    }
}
