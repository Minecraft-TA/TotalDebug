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
import java.util.function.BiPredicate;

/**
 * What Companion changed in the pack, kept per instance in {@code changes.json} (see docs/MODPACK.md). An entry holds a
 * target's value from before Companion first changed it and the value written last. Writing the original value back
 * removes the entry, so the record lists only changes still in effect. Targets are configuration settings and key
 * bindings.
 */
public final class ChangeRecord implements AutoCloseable {
    private static final int FORMAT = 1;

    /** Something Companion writes to. */
    public sealed interface Target permits Setting, KeyBinding {
    }

    /** A setting of a configuration file; {@code file} is where it was written, such as one world's server file. */
    public record Setting(String modId, String fileName, Path file, String setting) implements Target {
        public Setting {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(fileName, "fileName");
            file = file.toAbsolutePath().normalize();
            Objects.requireNonNull(setting, "setting");
        }
    }

    /**
     * A key binding of the instance's {@code options.txt}, by its name such as {@code key.jump}. Its values are written
     * as {@code options.txt} writes them: a key, with {@code :} and the modifier when there is one.
     */
    public record KeyBinding(String name) implements Target {
        public KeyBinding {
            Objects.requireNonNull(name, "name");
        }
    }

    /** A change still in effect: {@code original} is the value before the first change, {@code current} the last written. */
    public record Change(Target target, String original, String current, Instant firstChanged, Instant lastChanged) {
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
    /** The game directory the record's files are stored relative to, or null for a record that is not saved. */
    private final Path gameDirectory;
    private final Map<Target, Change> changes = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private ChangeRecord(JsonStateWriter writer, Clock clock, Path gameDirectory) {
        this.writer = writer;
        this.clock = clock;
        this.gameDirectory = gameDirectory;
    }

    /** A record that is not saved, for tests and projects without a data directory. */
    public static ChangeRecord inMemory() {
        return new ChangeRecord(null, Clock.systemUTC(), null);
    }

    static ChangeRecord inMemory(Clock clock) {
        return new ChangeRecord(null, clock, null);
    }

    /**
     * Opens the record of the instance whose game directory is {@code gameDirectory}. Files in the game directory are
     * stored relative to it, so a copied or moved instance changes its own files.
     */
    public static ChangeRecord open(InstancePaths paths, Path gameDirectory) throws IOException {
        Path game = gameDirectory.toAbsolutePath().normalize();
        ChangeRecord record = new ChangeRecord(new JsonStateWriter(paths.changes()), Clock.systemUTC(), game);
        if (!Files.exists(paths.changes())) return record;
        try {
            JsonObject json = JsonFiles.read(paths.changes());
            if (JsonFiles.integer(json, "format") != FORMAT) {
                throw new IOException("Unsupported change record format: " + paths.changes());
            }
            for (JsonElement element : JsonFiles.array(json, "changes")) {
                JsonObject entry = element.getAsJsonObject();
                Target target = switch (JsonFiles.string(entry, "kind")) {
                    case "setting" -> {
                        Path file = inInstance(game, JsonFiles.string(entry, "file"));
                        if (file == null) {
                            // A path outside the instance names another instance's file, such as the original of a copy.
                            System.err.println("Leaving out a change of " + JsonFiles.string(entry, "file")
                                    + ": it is not a file of this instance");
                            yield null;
                        }
                        yield new Setting(JsonFiles.string(entry, "modId"), JsonFiles.string(entry, "fileName"), file,
                                JsonFiles.string(entry, "setting"));
                    }
                    case "keyBinding" -> new KeyBinding(JsonFiles.string(entry, "name"));
                    default -> throw new IllegalArgumentException("Unknown change kind " + JsonFiles.string(entry, "kind"));
                };
                if (target == null) continue;
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
    public void changed(Target target, String previous, String written) {
        if (this.gameDirectory != null && target instanceof Setting setting
                && !setting.file().toAbsolutePath().normalize().startsWith(this.gameDirectory)) {
            throw new IllegalArgumentException(setting.file() + " is not a file of this instance");
        }
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
     * outside Companion. {@code sameValue} compares as the target's file does, such as {@code "a"} and {@code 'a'} in
     * TOML.
     */
    public void observed(Target target, String literal, BiPredicate<String, String> sameValue) {
        boolean dropped;
        synchronized (this) {
            Change change = this.changes.get(target);
            dropped = change != null && sameValue.test(change.original(), literal);
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
            if (change.target() instanceof Setting target && target.file().equals(normalized) && target.setting().equals(setting)) {
                return change.original();
            }
        }
        return null;
    }

    /** The value {@code target} had before Companion first changed it, or null when it is unchanged. */
    public synchronized String original(Target target) {
        Change change = this.changes.get(target);
        return change == null ? null : change.original();
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
            switch (change.target()) {
                case Setting setting -> {
                    entry.addProperty("kind", "setting");
                    entry.addProperty("modId", setting.modId());
                    entry.addProperty("fileName", setting.fileName());
                    entry.addProperty("file", stored(setting.file()));
                    entry.addProperty("setting", setting.setting());
                }
                case KeyBinding binding -> {
                    entry.addProperty("kind", "keyBinding");
                    entry.addProperty("name", binding.name());
                }
            }
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

    /** A file as the record stores it: relative to the game directory, with forward slashes. */
    private String stored(Path file) {
        return this.gameDirectory.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** The instance's file a stored path names, or null for an absolute path or one leading out of the instance. */
    private static Path inInstance(Path game, String stored) {
        Path relative = Path.of(stored);
        if (relative.isAbsolute()) return null;
        Path file = game.resolve(relative).normalize();
        return file.startsWith(game) ? file : null;
    }

    public void saveNow() throws IOException {
        if (this.writer != null) this.writer.flush();
    }

    @Override
    public void close() throws IOException {
        if (this.writer != null) this.writer.close();
    }
}
