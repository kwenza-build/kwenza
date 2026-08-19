package com.example.wordcounter;

import com.google.common.base.Splitter;

import java.util.List;

public final class WordCounter {

    private final WordFilter filter;

    public WordCounter(WordFilter filter) {
        this.filter = filter;
    }

    public long countValidWords(String text) {
        List<String> words = Splitter.on(' ').omitEmptyStrings().trimResults().splitToList(text);
        return words.stream().filter(filter::isValid).count();
    }
}
