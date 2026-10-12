package dev.kwenza.toml;

import dev.kwenza.context.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomlTest {

    @Test
    void parsesTopLevelKeyvalsOfEachScalarType() {
        Map<String, Object> result = Toml.parse("""
                name = "kwenza"
                version = 1
                pi = 3.5
                enabled = true
                disabled = false
                """).get();

        assertEquals("kwenza", result.get("name"));
        assertEquals(1L, result.get("version"));
        assertEquals(3.5, result.get("pi"));
        assertEquals(Boolean.TRUE, result.get("enabled"));
        assertEquals(Boolean.FALSE, result.get("disabled"));
    }

    @Test
    void parsesATableHeaderAsANestedMap() {
        Map<String, Object> result = Toml.parse("""
                [package]
                name = "kwenza"
                version = "0.1.0"
                """).get();

        @SuppressWarnings("unchecked")
        Map<String, Object> pkg = (Map<String, Object>) result.get("package");
        assertEquals("kwenza", pkg.get("name"));
        assertEquals("0.1.0", pkg.get("version"));
    }

    @Test
    void dottedKeysNestWithoutAnExplicitTableHeader() {
        Map<String, Object> result = Toml.parse("dependencies.guava.version = \"33.2.1-jre\"").get();

        @SuppressWarnings("unchecked")
        Map<String, Object> dependencies = (Map<String, Object>) result.get("dependencies");
        @SuppressWarnings("unchecked")
        Map<String, Object> guava = (Map<String, Object>) dependencies.get("guava");
        assertEquals("33.2.1-jre", guava.get("version"));
    }

    @Test
    void parsesAnArrayOfTablesAsAListOfMaps() {
        Map<String, Object> result = Toml.parse("""
                [[resolved]]
                module = "com.google.guava:guava"
                version = "33.2.1-jre"

                [[resolved]]
                module = "com.google.guava:failureaccess"
                version = "1.0.2"
                """).get();

        @SuppressWarnings("unchecked")
        List<Object> resolved = (List<Object>) result.get("resolved");
        assertEquals(2, resolved.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) resolved.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) resolved.get(1);
        assertEquals("com.google.guava:guava", first.get("module"));
        assertEquals("com.google.guava:failureaccess", second.get("module"));
    }

    @Test
    void parsesAnInlineTableAndAnArray() {
        Map<String, Object> result = Toml.parse("""
                guava = { module = "com.google.guava:guava", version = "33.2.1-jre" }
                items = ["kwenza", "builds", "java"]
                """).get();

        @SuppressWarnings("unchecked")
        Map<String, Object> guava = (Map<String, Object>) result.get("guava");
        assertEquals("com.google.guava:guava", guava.get("module"));
        assertEquals(List.of("kwenza", "builds", "java"), result.get("items"));
    }

    @Test
    void basicStringsInterpretEscapeSequences() {
        Map<String, Object> result = Toml.parse("greeting = \"line one\\nline two\\t(tabbed)\"").get();

        assertEquals("line one\nline two\t(tabbed)", result.get("greeting"));
    }

    @Test
    void literalStringsDoNotInterpretEscapeSequences() {
        Map<String, Object> result = Toml.parse("path = 'C:\\Users\\no\\escapes'").get();

        assertEquals("C:\\Users\\no\\escapes", result.get("path"));
    }

    @Test
    void lineCommentsAreIgnored() {
        Map<String, Object> result = Toml.parse("""
                # a full-line comment
                name = "kwenza" # a trailing comment
                """).get();

        assertEquals("kwenza", result.get("name"));
        assertEquals(1, result.size());
    }

    @Test
    void anUnterminatedStringIsAFailureNamingTheMissingQuote() {
        Result<Map<String, Object>> result = Toml.parse("name = \"kwenza");

        String problem = result.failure().problems().get(0);
        assertTrue(problem.contains("'\"'"), "expected the message to mention the missing quote: " + problem);
    }

    @Test
    void aMissingEqualsSignIsAFailure() {
        assertFalse(Toml.parse("name \"kwenza\"").isOk());
    }

    @Test
    void reusingATableKeyAsAPlainValueIsAFailure() {
        assertFalse(Toml.parse("""
                [package]
                name = "kwenza"

                [package.name]
                oops = true
                """).isOk());
    }
}
