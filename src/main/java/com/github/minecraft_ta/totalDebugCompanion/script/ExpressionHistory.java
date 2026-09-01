package com.github.minecraft_ta.totalDebugCompanion.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Small local history for transient Java evaluations. Results are never persisted. */
public final class ExpressionHistory {
    public static final int MAX_ENTRIES = 50;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    public ExpressionHistory(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        load();
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(this.entries);
    }

    public synchronized void record(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        this.entries.remove(entry);
        this.entries.addFirst(entry);
        if (this.entries.size() > MAX_ENTRIES) {
            this.entries.subList(MAX_ENTRIES, this.entries.size()).clear();
        }
        save();
    }

    private void load() {
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        try {
            List<Entry> loaded = GSON.fromJson(
                    Files.readString(this.file, StandardCharsets.UTF_8),
                    new TypeToken<List<Entry>>() { }.getType()
            );
            if (loaded != null) {
                this.entries.addAll(loaded.stream().limit(MAX_ENTRIES).toList());
            }
        } catch (IOException | RuntimeException exception) {
            System.err.println("Unable to read expression history " + this.file + ": " + exception.getMessage());
        }
    }

    private void save() {
        try {
            Path parent = this.file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path staged = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            Files.writeString(staged, GSON.toJson(this.entries), StandardCharsets.UTF_8);
            try {
                Files.move(staged, this.file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(staged, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            System.err.println("Unable to save expression history " + this.file + ": " + exception.getMessage());
        }
    }

    public record Entry(
            String expression,
            SnippetExecutionService.Side side,
            List<String> imports
    ) {
        public Entry {
            if (Objects.requireNonNull(expression, "expression").isBlank()) {
                throw new IllegalArgumentException("expression must not be blank");
            }
            side = Objects.requireNonNull(side, "side");
            imports = List.copyOf(imports);
        }
    }
}
