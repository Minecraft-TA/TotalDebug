package com.github.minecraft_ta.totalDebugCompanion.storage;

import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What Companion changed in the pack, kept per instance in {@code changes.json} (see docs/MODPACK.md). An entry holds a
 * target's value from before Companion first changed it and the value written last. Writing the original value back
 * removes the entry, so the record lists only changes still in effect.
 */
public final class ChangeRecord implements AutoCloseable {
    private static final int FORMAT = 1;

    /** A setting of a configuration file; {@code file} is where it was written, such as one world's server file. */
    public record Setting(String modId, String fileName, Path file, String setting) {
        public Setting {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(fileName, "fileName");
            file = file.toAbsolutePath().normalize();
            Objects.requireNonNull(setting, "setting");
        }
    }

    /** A change still in effect: {@code original} is the value before the first change, {@code current} the last written. */
    public record Change(Setting target, String original, String current, Instant firstChanged, Instant lastChanged) {
        public Change {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(firstChanged, "firstChanged");
            Objects.requireNonNull(lastChanged, "lastChanged");
        }
    }

    private final JsonStateWriter writer;
    private final Clock clock;
    private final Map<Setting, Change> changes = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private ChangeRecord(JsonStateWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    /** A record that is not saved, for tests and projects without a data directory. */
    public static ChangeRecord inMemory() {
        return new ChangeRecord(null, Clock.systemUTC());
    }

    static ChangeRecord inMemory(Clock clock) {
        return new ChangeRecord(null, clock);
    }

    public static ChangeRecord open(InstancePaths paths) throws IOException {
        ChangeRecord record = new ChangeRecord(new JsonStateWriter(paths.changes()), Clock.systemUTC());
        if (!Files.exists(paths.changes())) return record;
        try {
            JsonObject json = JsonFiles.read(paths.changes());
            if (JsonFiles.integer(json, "format") != FORMAT) {
                throw new IOException("Unsupported change record format: " + paths.changes());
            }
            for (JsonElement element : JsonFiles.array(json, "changes")) {
                JsonObject entry = element.getAsJsonObject();
                Setting target = new Setting(JsonFiles.string(entry, "modId"), JsonFiles.string(entry, "fileName"),
                        Path.of(JsonFiles.string(entry, "file")), JsonFiles.string(entry, "setting"));
                record.changes.put(target, new Change(target, JsonFiles.string(entry, "original"),
                        JsonFiles.string(entry, "current"), Instant.parse(JsonFiles.string(entry, "firstChanged")),
                        Instant.parse(JsonFiles.string(entry, "lastChanged"))));
            }
            return record;
        } catch (IOException | RuntimeException exception) {
            record.writer.close();
            throw new IOException("Invalid change record " + paths.changes() + ": " + exception.getMessage(), exception);
        }
    }

    /** Records that {@code target} changed from {@code previous} to {@code written}. */
    public void changed(Setting target, String previous, String written) {
        synchronized (this) {
            Change earlier = this.changes.get(target);
            String original = earlier == null ? previous : earlier.original();
            if (original.equals(written)) {
                this.changes.remove(target);
            } else {
                Instant now = this.clock.instant();
                this.changes.put(target, new Change(target, original, written,
                        earlier == null ? now : earlier.firstChanged(), now));
            }
            scheduleSave();
        }
        this.listeners.forEach(Runnable::run);
    }

    /**
     * Drops the change of {@code target} when the file holds its original value again, such as after an edit made
     * outside Companion.
     */
    public void observed(Setting target, String literal) {
        boolean dropped;
        synchronized (this) {
            Change change = this.changes.get(target);
            dropped = change != null && change.original().equals(literal);
            if (dropped) {
                this.changes.remove(target);
                scheduleSave();
            }
        }
        if (dropped) this.listeners.forEach(Runnable::run);
    }

    /** The value a setting of {@code file} had before Companion first changed it, or null when it is unchanged. */
    public synchronized String original(Path file, String setting) {
        Path normalized = file.toAbsolutePath().normalize();
        for (Change change : this.changes.values()) {
            if (change.target().file().equals(normalized) && change.target().setting().equals(setting)) return change.original();
        }
        return null;
    }

    /** Changes still in effect, the most recent first. */
    public synchronized List<Change> changes() {
        List<Change> sorted = new ArrayList<>(this.changes.values());
        sorted.sort(Comparator.comparing(Change::lastChanged).reversed());
        return List.copyOf(sorted);
    }

    public synchronized int size() {
        return this.changes.size();
    }

    /** Runs {@code listener} after every change of the record, on the thread that changed it; returns its removal. */
    public Runnable addListener(Runnable listener) {
        this.listeners.add(listener);
        return () -> this.listeners.remove(listener);
    }

    private void scheduleSave() {
        if (this.writer == null) return;
        JsonArray entries = new JsonArray();
        for (Change change : this.changes.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("modId", change.target().modId());
            entry.addProperty("fileName", change.target().fileName());
            entry.addProperty("file", change.target().file().toString());
            entry.addProperty("setting", change.target().setting());
            entry.addProperty("original", change.original());
            entry.addProperty("current", change.current());
            entry.addProperty("firstChanged", change.firstChanged().toString());
            entry.addProperty("lastChanged", change.lastChanged().toString());
            entries.add(entry);
        }
        JsonObject json = new JsonObject();
        json.addProperty("format", FORMAT);
        json.add("changes", entries);
        this.writer.schedule(json);
    }

    public void saveNow() throws IOException {
        if (this.writer != null) this.writer.flush();
    }

    @Override
    public void close() throws IOException {
        if (this.writer != null) this.writer.close();
    }
}
