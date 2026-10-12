package dev.kwenza.toml;

import dev.kwenza.context.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal hand-rolled TOML reader covering the subset Kwenza needs: tables,
 * array-of-tables, dotted keys, basic/literal strings, integers, booleans,
 * arrays and inline tables. Not a full spec-compliant TOML implementation
 * (no dates, no triple-quoted strings, no hex/octal/binary integers).
 */
public final class Toml {

    private final String src;
    private int pos;

    private Toml(String src) {
        this.src = src;
        this.pos = 0;
    }

    /** The document as nested maps and lists, or a failure describing the first syntax error. */
    public static Result<Map<String, Object>> parse(String text) {
        try {
            return Result.ok(new Toml(text).parseDocument());
        } catch (TomlParseException e) {
            return Result.err(e.getMessage());
        }
    }

    private Map<String, Object> parseDocument() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        skipWhitespaceAndComments();
        while (!eof()) {
            if (peek() == '[') {
                if (peekAt(1) == '[') {
                    current = enterArrayTable(root);
                } else {
                    current = enterTable(root);
                }
            } else {
                parseKeyval(current);
            }
            skipWhitespaceAndComments();
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> enterTable(Map<String, Object> root) {
        expect('[');
        List<String> path = parseKeyPath();
        expect(']');
        Map<String, Object> parent = navigateTables(root, path.subList(0, path.size() - 1));
        String last = path.get(path.size() - 1);
        Object existing = parent.get(last);
        if (existing instanceof Map) {
            return (Map<String, Object>) existing;
        }
        if (existing instanceof List) {
            List<Object> list = (List<Object>) existing;
            Object lastElem = list.get(list.size() - 1);
            if (lastElem instanceof Map) {
                return (Map<String, Object>) lastElem;
            }
            throw new TomlParseException("Key '" + last + "' is not a table");
        }
        if (existing != null) {
            throw new TomlParseException("Key '" + last + "' is not a table");
        }
        Map<String, Object> table = new LinkedHashMap<>();
        parent.put(last, table);
        return table;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> enterArrayTable(Map<String, Object> root) {
        expect('[');
        expect('[');
        List<String> path = parseKeyPath();
        expect(']');
        expect(']');
        Map<String, Object> parent = navigateTables(root, path.subList(0, path.size() - 1));
        String last = path.get(path.size() - 1);
        Object existing = parent.get(last);
        List<Object> list;
        if (existing instanceof List) {
            list = (List<Object>) existing;
        } else if (existing == null) {
            list = new ArrayList<>();
            parent.put(last, list);
        } else {
            throw new TomlParseException("Key '" + last + "' is not an array of tables");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        list.add(entry);
        return entry;
    }

    /** Walks (creating as needed) nested tables for all but the final key segment. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> navigateTables(Map<String, Object> root, List<String> segments) {
        Map<String, Object> node = root;
        for (String segment : segments) {
            Object existing = node.get(segment);
            if (existing instanceof Map) {
                node = (Map<String, Object>) existing;
            } else if (existing instanceof List) {
                List<Object> list = (List<Object>) existing;
                Object lastElem = list.get(list.size() - 1);
                if (!(lastElem instanceof Map)) {
                    throw new TomlParseException("Key '" + segment + "' is not a table");
                }
                node = (Map<String, Object>) lastElem;
            } else if (existing == null) {
                Map<String, Object> table = new LinkedHashMap<>();
                node.put(segment, table);
                node = table;
            } else {
                throw new TomlParseException("Key '" + segment + "' is not a table");
            }
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private void parseKeyval(Map<String, Object> current) {
        List<String> path = parseKeyPath();
        skipInlineWhitespace();
        expect('=');
        skipInlineWhitespace();
        Object value = parseValue();
        Map<String, Object> target = navigateTables(current, path.subList(0, path.size() - 1));
        target.put(path.get(path.size() - 1), value);
        skipInlineWhitespace();
        skipLineComment();
    }

    private List<String> parseKeyPath() {
        List<String> segments = new ArrayList<>();
        segments.add(parseSimpleKey());
        skipInlineWhitespace();
        while (!eof() && peek() == '.') {
            advance();
            skipInlineWhitespace();
            segments.add(parseSimpleKey());
            skipInlineWhitespace();
        }
        return segments;
    }

    private String parseSimpleKey() {
        skipInlineWhitespace();
        if (eof()) {
            throw new TomlParseException("Unexpected end of input while reading a key");
        }
        char c = peek();
        if (c == '"') {
            return parseBasicString();
        }
        if (c == '\'') {
            return parseLiteralString();
        }
        StringBuilder sb = new StringBuilder();
        while (!eof() && isBareKeyChar(peek())) {
            sb.append(advance());
        }
        if (sb.length() == 0) {
            throw new TomlParseException("Expected a key at position " + pos);
        }
        return sb.toString();
    }

    private boolean isBareKeyChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }

    private Object parseValue() {
        skipInlineWhitespace();
        if (eof()) {
            throw new TomlParseException("Unexpected end of input while reading a value");
        }
        char c = peek();
        if (c == '"') {
            return parseBasicString();
        }
        if (c == '\'') {
            return parseLiteralString();
        }
        if (c == '[') {
            return parseArray();
        }
        if (c == '{') {
            return parseInlineTable();
        }
        if (c == 't' && src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (c == 'f' && src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        if (c == '-' || c == '+' || Character.isDigit(c)) {
            return parseNumber();
        }
        throw new TomlParseException("Unexpected character '" + c + "' at position " + pos);
    }

    private Object parseNumber() {
        StringBuilder sb = new StringBuilder();
        boolean isFloat = false;
        if (peek() == '-' || peek() == '+') {
            sb.append(advance());
        }
        while (!eof() && (Character.isDigit(peek()) || peek() == '_')) {
            char ch = advance();
            if (ch != '_') sb.append(ch);
        }
        if (!eof() && peek() == '.') {
            isFloat = true;
            sb.append(advance());
            while (!eof() && (Character.isDigit(peek()) || peek() == '_')) {
                char ch = advance();
                if (ch != '_') sb.append(ch);
            }
        }
        if (!eof() && (peek() == 'e' || peek() == 'E')) {
            isFloat = true;
            sb.append(advance());
            if (!eof() && (peek() == '-' || peek() == '+')) {
                sb.append(advance());
            }
            while (!eof() && Character.isDigit(peek())) {
                sb.append(advance());
            }
        }
        String text = sb.toString();
        return isFloat ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
    }

    private List<Object> parseArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespaceAndComments();
        while (!eof() && peek() != ']') {
            list.add(parseValue());
            skipWhitespaceAndComments();
            if (!eof() && peek() == ',') {
                advance();
                skipWhitespaceAndComments();
            } else {
                break;
            }
        }
        skipWhitespaceAndComments();
        expect(']');
        return list;
    }

    private Map<String, Object> parseInlineTable() {
        expect('{');
        Map<String, Object> table = new LinkedHashMap<>();
        skipInlineWhitespace();
        while (!eof() && peek() != '}') {
            List<String> path = parseKeyPath();
            skipInlineWhitespace();
            expect('=');
            skipInlineWhitespace();
            Object value = parseValue();
            Map<String, Object> target = navigateTables(table, path.subList(0, path.size() - 1));
            target.put(path.get(path.size() - 1), value);
            skipInlineWhitespace();
            if (!eof() && peek() == ',') {
                advance();
                skipInlineWhitespace();
            } else {
                break;
            }
        }
        skipInlineWhitespace();
        expect('}');
        return table;
    }

    private String parseBasicString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (!eof() && peek() != '"') {
            char c = advance();
            if (c == '\\') {
                if (eof()) {
                    throw new TomlParseException("Unterminated escape sequence in string");
                }
                char esc = advance();
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': {
                        String hex = src.substring(pos, pos + 4);
                        pos += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                        break;
                    }
                    default:
                        throw new TomlParseException("Unknown escape sequence '\\" + esc + "'");
                }
            } else {
                sb.append(c);
            }
        }
        expect('"');
        return sb.toString();
    }

    private String parseLiteralString() {
        expect('\'');
        StringBuilder sb = new StringBuilder();
        while (!eof() && peek() != '\'') {
            sb.append(advance());
        }
        expect('\'');
        return sb.toString();
    }

    private void skipWhitespaceAndComments() {
        while (!eof()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                advance();
            } else if (c == '#') {
                while (!eof() && peek() != '\n') {
                    advance();
                }
            } else {
                break;
            }
        }
    }

    private void skipInlineWhitespace() {
        while (!eof() && (peek() == ' ' || peek() == '\t')) {
            advance();
        }
    }

    private void skipLineComment() {
        if (!eof() && peek() == '#') {
            while (!eof() && peek() != '\n') {
                advance();
            }
        }
    }

    private boolean eof() {
        return pos >= src.length();
    }

    private char peek() {
        return src.charAt(pos);
    }

    private char peekAt(int offset) {
        int p = pos + offset;
        return p < src.length() ? src.charAt(p) : '\0';
    }

    private char advance() {
        return src.charAt(pos++);
    }

    private void expect(char c) {
        if (eof() || peek() != c) {
            throw new TomlParseException("Expected '" + c + "' at position " + pos
                    + (eof() ? " but reached end of input" : " but found '" + peek() + "'"));
        }
        advance();
    }
}
