package com.example;

import com.google.common.collect.ImmutableList;

public final class Main {
    public static void main(String[] args) {
        ImmutableList<String> items = ImmutableList.of("kwenza", "builds", "java");
        System.out.println("Hello from Kwenza: " + String.join(" ", items));
    }
}
