package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangeCategory;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.change.Effect;
import com.github.minecraft_ta.totalDebugCompanion.game.Access;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Mods' configuration settings as a category of the change pipeline (see {@code docs/CHANGE_PIPELINE.md}): always written
 * to their file, which NeoForge's config watcher applies in a running game, one setting in place or a file's whole text.
 * A setting's value is its TOML literal as the file writes it, such as {@code 5} or {@code "a"}. {@link ConfigChanges}
 * tells when the game uses a written value.
 */
public final class ConfigSettings implements ChangeCategory<ChangeRecord.Setting, ConfigSettings.Value> {
    /** A mod's configuration file, where it is written. */
    public record FileTarget(String modId, String fileName, Path file, PackCatalog.ConfigType type) {
        public FileTarget {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(type, "type");
        }
    }

    /** A setting of a mod's configuration file, in the file it is written to. */
    public record Target(String modId, String fileName, Path file, PackCatalog.ConfigType type, PackCatalog.ConfigSetting setting) {
        public Target {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(setting, "setting");
        }

        ChangeRecord.Setting recorded() {
            return new ChangeRecord.Setting(this.modId, this.fileName, this.file, this.setting.path());
        }
    }

    /** The file no longer holds the text an edit was made against. */
    public static final class ConflictException extends IOException {
        ConflictException(Path file) {
            super(file.getFileName() + " changed on disk since it was opened");
        }
    }

    /**
     * A setting's new literal; with {@code text}, the whole text of its file saved at once, and {@code base}, the text it
     * was edited from, or null to replace whatever the file holds.
     */
    public record Value(String literal, String text, String base) {
        public Value {
            Objects.requireNonNull(literal, "literal");
        }
    }

    /** A setting a write changed: its value before and after, and when the game uses the new one. */
    public record Changed(Target target, String before, String after, Effect effect) {
    }

    /** What a write did, and what to tell of it. */
    public record Saved(List<Changed> changed, String message) {
    }

    private final ConfigChanges changes;
    private final ChangePipeline pipeline;

    /** Writes through {@code pipeline}, and tells with {@code changes} when the game uses each value. */
    public ConfigSettings(ConfigChanges changes, ChangePipeline pipeline) {
        this.changes = Objects.requireNonNull(changes, "changes");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    /** When the game uses what was written, and which edits the running game still waits for. */
    public ConfigChanges changes() {
        return this.changes;
    }

    public GameLocation location() {
        return this.changes.location();
    }

    public ChangeRecord record() {
        return this.pipeline.record();
    }

    /**
     * Sets {@code target} to {@code literal}, when it still holds {@code expected}, the value shown when the edit was
     * made, or whatever it holds where {@code expected} is null.
     */
    public CompletableFuture<Saved> set(Target target, String expected, String literal) {
        return this.pipeline.change(this, List.of(new ChangePipeline.Edit<>(target.recorded(), expected, new Value(literal, null, null))))
                .thenApply(outcome -> {
                    ChangePipeline.Applied<ChangeRecord.Setting> applied = outcome.applied().getFirst();
                    Changed changed = changed(target, applied.before(), applied.now());
                    return new Saved(List.of(changed), target.setting().name() + " saved, " + changed.effect().description());
                });
    }

    /**
     * Writes a file's edited text {@code after} in place of {@code base}, the text it was edited from, as one change of
     * every setting it changes. When the file holds other text by now, it fails with {@link ConflictException} unless
     * {@code overwrite}.
     */
    public CompletableFuture<Saved> saveText(FileTarget target, List<PackCatalog.ConfigSetting> settings, String base, String after,
                                             boolean overwrite) {
        List<ConfigEdit.TextChange> changes = ConfigEdit.changes(base, after, settings);
        String fileName = target.fileName().substring(target.fileName().lastIndexOf('/') + 1);
        if (changes.isEmpty()) {
            // A text that changes no setting, such as its comments, is a file write the change record does not keep.
            return this.changes.write(() -> {
                try {
                    writeText(target.file(), after, overwrite ? null : base);
                    return new Saved(List.of(), fileName + " saved");
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            });
        }
        Map<ChangeRecord.Setting, Target> targets = new LinkedHashMap<>();
        List<ChangePipeline.Edit<ChangeRecord.Setting, Value>> edits = new ArrayList<>();
        for (ConfigEdit.TextChange change : changes) {
            Target setting = new Target(target.modId(), target.fileName(), target.file(), target.type(), change.setting());
            targets.put(setting.recorded(), setting);
            // The whole text is checked against the file instead, so a setting of it expects nothing on its own.
            edits.add(new ChangePipeline.Edit<>(setting.recorded(), null, new Value(change.after(), after, overwrite ? null : base)));
        }
        return this.pipeline.change(this, edits).thenApply(outcome -> {
            List<Changed> changed = outcome.applied().stream()
                    .map(applied -> changed(targets.get(applied.target()), applied.before(), applied.now())).toList();
            return new Saved(changed, textMessage(fileName, changed));
        });
    }

    /** The value a setting had before Companion first changed it, or null when Companion did not change it. */
    public String original(Path file, String setting) {
        return this.changes.original(file, setting);
    }

    /** What the running game waits for before it uses the edited value of a setting, or null. */
    public Effect pending(Path file, String setting) {
        return this.changes.pending(file, setting);
    }

    /** Drops pending rejoin edits whose world has closed. Blocking; not on the Swing thread. */
    public void refresh() {
        this.changes.refresh();
    }

    private Changed changed(Target target, String before, String after) {
        return new Changed(target, before, after, this.changes.edited(target.recorded(), target.type(), target.setting().restart(), before, after));
    }

    /**
     * What saving a text did: the one setting it changed and when the game uses it, or how many settings changed and
     * the one the game waits longest for.
     */
    private static String textMessage(String fileName, List<Changed> changed) {
        if (changed.isEmpty()) return fileName + " saved";
        if (changed.size() == 1) return changed.getFirst().target().setting().name() + " saved, " + changed.getFirst().effect().description();
        Changed waiting = null;
        for (Changed setting : changed) {
            if (setting.effect().pending() && (waiting == null || setting.effect() == Effect.RESTART)) waiting = setting;
        }
        String saved = changed.size() + " settings saved";
        if (waiting != null) return saved + ", " + waiting.target().setting().name() + " " + waiting.effect().description();
        return changed.stream().allMatch(setting -> setting.effect() == Effect.NOW) ? saved + ", the game reloaded them" : saved;
    }

    @Override
    public String id() {
        return "config";
    }

    @Override
    public String name(ChangeRecord.Setting target) {
        return target.setting() + " in " + target.file().getFileName();
    }

    /** A configuration file is written whether or not a game runs: NeoForge's config watcher applies it, or the next start. */
    @Override
    public Access access(GameState game, ChangeRecord.Setting target) {
        return new Access.Files();
    }

    @Override
    public String text(Value value) {
        return value.literal();
    }

    @Override
    public Map<ChangeRecord.Setting, String> readFile(Collection<ChangeRecord.Setting> targets) throws IOException {
        Map<Path, Map<String, String>> files = new HashMap<>();
        Map<ChangeRecord.Setting, String> values = new HashMap<>();
        for (ChangeRecord.Setting target : targets) {
            Map<String, String> literals = files.get(target.file());
            if (literals == null) {
                literals = ConfigValues.read(target.file()).literals();
                files.put(target.file(), literals);
            }
            String literal = literals.get(target.setting());
            if (literal != null) values.put(target, literal);
        }
        return values;
    }

    /**
     * Writes each file's settings: a file saved as text at once, after checking it still holds the text it was edited
     * from; otherwise each setting in place, reading the file again first so edits made meanwhile are kept.
     */
    @Override
    public void writeFile(List<Write<ChangeRecord.Setting, Value>> writes, Consumer<ChangeRecord.Setting> landed) throws IOException {
        Map<Path, List<Write<ChangeRecord.Setting, Value>>> byFile = new LinkedHashMap<>();
        for (Write<ChangeRecord.Setting, Value> write : writes) byFile.computeIfAbsent(write.target().file(), file -> new ArrayList<>()).add(write);
        for (Map.Entry<Path, List<Write<ChangeRecord.Setting, Value>>> file : byFile.entrySet()) {
            Value whole = file.getValue().getFirst().value();
            if (whole.text() != null) {
                writeText(file.getKey(), whole.text(), whole.base());
                file.getValue().forEach(write -> landed.accept(write.target()));
                continue;
            }
            for (Write<ChangeRecord.Setting, Value> write : file.getValue()) {
                ConfigEdit.write(file.getKey(), write.target().setting(), write.value().literal());
                landed.accept(write.target());
            }
        }
    }

    /** Writes {@code text} into {@code file}, when it still holds {@code base}, or whatever it holds where that is null. */
    private static void writeText(Path file, String text, String base) throws IOException {
        if (base != null && !Files.readString(file, StandardCharsets.UTF_8).equals(base)) throw new ConflictException(file);
        ConfigEdit.writeInPlace(file, text);
    }
}
