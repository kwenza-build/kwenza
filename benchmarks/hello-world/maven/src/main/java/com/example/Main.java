package com.example;

import com.google.common.collect.ImmutableList;

public final class Main {
    public static void main(String[] args) {
        ImmutableList<String> items = ImmutableList.of("hello", "from", "the", "build");
        System.out.println(String.join(" ", items));
    }
}
