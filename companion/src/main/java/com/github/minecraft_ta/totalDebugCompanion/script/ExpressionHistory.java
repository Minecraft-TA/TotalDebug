package com.github.minecraft_ta.totalDebugCompanion.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Input recall only; execution results are never persisted. */
public final class ExpressionHistory {
    public static final int MAX_ENTRIES = 50;
    private final List<Entry> entries;
    private final Consumer<List<Entry>> changed;

    public ExpressionHistory(List<Entry> entries, Consumer<List<Entry>> changed) {
        this.entries = new ArrayList<>(List.copyOf(entries));
        if (this.entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many expression history entries");
        }
        this.changed = Objects.requireNonNull(changed);
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(this.entries);
    }

    public synchronized void record(Entry entry) {
        this.entries.remove(Objects.requireNonNull(entry));
        this.entries.addFirst(entry);
        if (this.entries.size() > MAX_ENTRIES) {
            this.entries.removeLast();
        }
        this.changed.accept(List.copyOf(this.entries));
    }

    public record Entry(String expression, SnippetExecutionService.Side side, List<String> imports,
                        com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource.Mode mode) {
        public Entry(String expression, SnippetExecutionService.Side side, List<String> imports) {
            this(expression, side, imports, com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource.Mode.EXPRESSION);
        }
        public Entry {
            if (Objects.requireNonNull(expression).isBlank()) {
                throw new IllegalArgumentException("expression must not be blank");
            }
            Objects.requireNonNull(side);
            Objects.requireNonNull(mode);
            imports = List.copyOf(imports);
        }
    }
}
