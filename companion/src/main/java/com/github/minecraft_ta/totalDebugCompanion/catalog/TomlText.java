package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A TOML file's text with where each value is written, keyed by dotted path like {@link ConfigValues}. Replacing a
 * value changes only its own characters, so every comment, blank line and indent stays as the file had it. Entries of
 * arrays of tables are not addressable; they are never part of a NeoForge configuration.
 */
public final class TomlText {
    private record Span(int start, int end) {
    }

    private final String text;
    private final Map<String, Span> values;

    private TomlText(String text, Map<String, Span> values) {
        this.text = text;
        this.values = values;
    }

    /** Finds the values of a file's text. Throws {@link IllegalArgumentException} where the text is not TOML. */
    public static TomlText of(String text) {
        return new TomlText(text, Collections.unmodifiableMap(new Scanner(text).scan()));
    }

    public String text() {
        return this.text;
    }

    /** The value of {@code key} as it is written, or null when the file does not set it. */
    public String literal(String key) {
        Span span = this.values.get(key);
        return span == null ? null : this.text.substring(span.start(), span.end());
    }

    /** Every value as it is written, in file order. */
    public Map<String, String> literals() {
        Map<String, String> literals = new LinkedHashMap<>();
        this.values.forEach((key, span) -> literals.put(key, this.text.substring(span.start(), span.end())));
        return literals;
    }

    /** The text with the value of {@code key} written as {@code literal}. */
    public String with(String key, String literal) {
        Span span = this.values.get(key);
        if (span == null) throw new IllegalArgumentException(key + " is not in the file");
        return this.text.substring(0, span.start()) + literal + this.text.substring(span.end());
    }

    private static final class Scanner {
        private final String text;
        private final Map<String, Span> values = new LinkedHashMap<>();
        private int position;
        private List<String> table = List.of();
        private boolean arrayTable;

        private Scanner(String text) {
            this.text = text;
        }

        Map<String, Span> scan() {
            while (true) {
                skipBlank(true);
                if (this.position >= this.text.length()) return this.values;
                char next = this.text.charAt(this.position);
                if (next == '[') {
                    header();
                } else {
                    entry();
                }
                skipBlank(false);
                if (this.position < this.text.length() && !atLineEnd()) throw problem("Expected the end of the line");
            }
        }

        private void header() {
            this.arrayTable = this.text.startsWith("[[", this.position);
            this.position += this.arrayTable ? 2 : 1;
            List<String> path = key();
            expect(this.arrayTable ? "]]" : "]");
            this.table = path;
        }

        private void entry() {
            List<String> key = key();
            expect("=");
            skipBlank(false);
            int start = this.position;
            value();
            if (this.arrayTable) return;
            List<String> path = new ArrayList<>(this.table);
            path.addAll(key);
            this.values.put(String.join(".", path), new Span(start, this.position));
        }

        /** A dotted key of bare and quoted parts. */
        private List<String> key() {
            List<String> parts = new ArrayList<>();
            while (true) {
                skipBlank(false);
                if (this.position >= this.text.length()) throw problem("Expected a key");
                char next = this.text.charAt(this.position);
                if (next == '"' || next == '\'') {
                    int start = this.position;
                    string();
                    parts.add(unquote(this.text.substring(start, this.position)));
                } else {
                    int start = this.position;
                    while (this.position < this.text.length() && bare(this.text.charAt(this.position))) this.position++;
                    if (start == this.position) throw problem("Expected a key");
                    parts.add(this.text.substring(start, this.position));
                }
                skipBlank(false);
                if (this.position < this.text.length() && this.text.charAt(this.position) == '.') {
                    this.position++;
                } else {
                    return parts;
                }
            }
        }

        private void value() {
            if (this.position >= this.text.length()) throw problem("Expected a value");
            char next = this.text.charAt(this.position);
            switch (next) {
                case '"', '\'' -> string();
                case '[' -> array();
                case '{' -> inlineTable();
                default -> {
                    int start = this.position;
                    while (this.position < this.text.length() && !ends(this.text.charAt(this.position))) this.position++;
                    // A date and time may be separated by one space.
                    if (this.position + 1 < this.text.length() && this.text.charAt(this.position) == ' '
                            && Character.isDigit(this.text.charAt(this.position + 1))
                            && this.text.substring(start, this.position).matches("\\d{4}-\\d{2}-\\d{2}")) {
                        this.position++;
                        while (this.position < this.text.length() && !ends(this.text.charAt(this.position))) this.position++;
                    }
                    if (start == this.position) throw problem("Expected a value");
                }
            }
        }

        private void array() {
            this.position++;
            while (true) {
                skipBlank(true);
                if (this.position >= this.text.length()) throw problem("Unclosed array");
                if (this.text.charAt(this.position) == ']') {
                    this.position++;
                    return;
                }
                value();
                skipBlank(true);
                if (this.position < this.text.length() && this.text.charAt(this.position) == ',') this.position++;
            }
        }

        private void inlineTable() {
            this.position++;
            while (true) {
                skipBlank(false);
                if (this.position >= this.text.length()) throw problem("Unclosed inline table");
                if (this.text.charAt(this.position) == '}') {
                    this.position++;
                    return;
                }
                key();
                expect("=");
                skipBlank(false);
                value();
                skipBlank(false);
                if (this.position < this.text.length() && this.text.charAt(this.position) == ',') this.position++;
            }
        }

        private void string() {
            char quote = this.text.charAt(this.position);
            String triple = String.valueOf(quote).repeat(3);
            boolean multiline = this.text.startsWith(triple, this.position);
            this.position += multiline ? 3 : 1;
            while (this.position < this.text.length()) {
                char next = this.text.charAt(this.position);
                if (quote == '"' && next == '\\') {
                    this.position += 2;
                } else if (multiline && this.text.startsWith(triple, this.position)) {
                    this.position += 3;
                    // Up to two quotes may directly follow the closing delimiter as part of the string.
                    while (this.position < this.text.length() && this.text.charAt(this.position) == quote) this.position++;
                    return;
                } else if (!multiline && next == quote) {
                    this.position++;
                    return;
                } else if (!multiline && (next == '\n' || next == '\r')) {
                    throw problem("Unclosed string");
                } else {
                    this.position++;
                }
            }
            throw problem("Unclosed string");
        }

        /** Skips spaces and comments, and line breaks too when {@code lines}. */
        private void skipBlank(boolean lines) {
            while (this.position < this.text.length()) {
                char next = this.text.charAt(this.position);
                if (next == ' ' || next == '\t' || lines && (next == '\n' || next == '\r')) {
                    this.position++;
                } else if (next == '#') {
                    while (this.position < this.text.length() && !atLineEnd()) this.position++;
                } else {
                    return;
                }
            }
        }

        private boolean atLineEnd() {
            char next = this.text.charAt(this.position);
            return next == '\n' || next == '\r';
        }

        private void expect(String token) {
            skipBlank(false);
            if (!this.text.startsWith(token, this.position)) throw problem("Expected " + token);
            this.position += token.length();
        }

        private IllegalArgumentException problem(String message) {
            int line = 1;
            for (int index = 0; index < Math.min(this.position, this.text.length()); index++) {
                if (this.text.charAt(index) == '\n') line++;
            }
            return new IllegalArgumentException(message + " on line " + line);
        }

        private static boolean bare(char character) {
            return Character.isLetterOrDigit(character) || character == '_' || character == '-';
        }

        private static boolean ends(char character) {
            return character == ' ' || character == '\t' || character == '\n' || character == '\r' || character == '#'
                    || character == ',' || character == ']' || character == '}';
        }

        /** A quoted key's name; keys with escapes are rare enough in configurations that the escapes stay as written. */
        private static String unquote(String quoted) {
            return quoted.substring(1, quoted.length() - 1);
        }
    }
}
