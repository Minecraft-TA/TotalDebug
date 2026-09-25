package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigEdit;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Writes configuration edits off the Swing thread, tells when the game uses each one, and keeps them for undo and
 * redo. Edits of several files share one history, so undo steps back through them in the order they were made; a
 * saved text is one step.
 */
final class ConfigWriter {
    /** A mod's configuration file, where it is written. */
    record FileTarget(String modId, String fileName, Path file, PackCatalog.ConfigType type) {
        FileTarget {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(type, "type");
        }
    }

    /** A setting of a mod's configuration file, in the file it is written to. */
    record Target(String modId, String fileName, Path file, PackCatalog.ConfigType type, PackCatalog.ConfigSetting setting) {
        Target {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(setting, "setting");
        }
    }

    /** The file no longer holds the text an edit was made against. */
    static final class ConflictException extends IOException {
        ConflictException(Path file) {
            super(file.getFileName() + " changed on disk since it was opened");
        }
    }

    /** A step of the history: one setting's value, or a file's whole text, with what it replaced. */
    private sealed interface Step permits SettingStep, TextStep {
        Step reversed();
    }

    private record SettingStep(Target target, String before, String after) implements Step {
        @Override
        public Step reversed() {
            return new SettingStep(this.target, this.after, this.before);
        }
    }

    private record TextStep(FileTarget target, List<PackCatalog.ConfigSetting> settings, String before, String after)
            implements Step {
        @Override
        public Step reversed() {
            return new TextStep(this.target, this.settings, this.after, this.before);
        }
    }

    private record Saved(Step step, String message) {
    }

    /** One file write at a time across Companion, so writes to the same file never interleave. */
    private static final ExecutorService WRITES = Executors.newSingleThreadExecutor(task ->
            Thread.ofPlatform().daemon().name("Configuration writes").unstarted(task));

    private final ConfigChanges changes;
    private final Consumer<String> status;
    private final Runnable written;
    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();

    /** {@code status} shows the outcome of each write, and {@code written} runs after a write succeeded. */
    ConfigWriter(ConfigChanges changes, Consumer<String> status, Runnable written) {
        this.changes = Objects.requireNonNull(changes, "changes");
        this.status = Objects.requireNonNull(status, "status");
        this.written = Objects.requireNonNull(written, "written");
    }

    /** What the running game waits for before it uses the edited value of a setting, or null. */
    ConfigChanges.Effect pending(Path file, String setting) {
        return this.changes.pending(file, setting);
    }

    /** The value a setting had before Companion first changed it, as the file wrote it, or null. */
    String original(Path file, String setting) {
        return this.changes.original(file, setting);
    }

    /** Drops pending rejoin edits whose world has closed. Blocking; not on the Swing thread. */
    void refreshPending() {
        this.changes.refresh();
    }

    /** Writes {@code after} in place of {@code before}, the value shown when the edit was made. */
    void edit(Target target, String before, String after) {
        write(new SettingStep(target, before, after), false, this::done, () -> { }, null);
    }

    /**
     * Writes a file's edited text in place of {@code before}, the text it was edited from. When the file holds other
     * text by now, {@code conflict} runs instead unless {@code overwrite}; {@code saved} runs after the write.
     */
    void saveText(FileTarget target, List<PackCatalog.ConfigSetting> settings, String before, String after,
                  boolean overwrite, Runnable saved, Runnable conflict) {
        write(new TextStep(target, List.copyOf(settings), before, after), overwrite, step -> {
            done(step);
            saved.run();
        }, () -> { }, Objects.requireNonNull(conflict, "conflict"));
    }

    private void done(Step step) {
        this.undo.push(step);
        this.redo.clear();
    }

    void undo() {
        Step step = this.undo.poll();
        if (step != null) write(step.reversed(), false, written -> this.redo.push(step), () -> this.undo.push(step), null);
    }

    void redo() {
        Step step = this.redo.poll();
        if (step != null) write(step, false, this.undo::push, () -> this.redo.push(step), null);
    }

    /** Undoes with Ctrl+Z and redoes with Ctrl+Shift+Z or Ctrl+Y while {@code component} has focus. */
    void bindUndo(JComponent component) {
        bind(component, "ctrl Z", "undoConfigEdit", this::undo);
        bind(component, "ctrl shift Z", "redoConfigEdit", this::redo);
        bind(component, "ctrl Y", "redoConfigEdit", this::redo);
    }

    private static void bind(JComponent component, String keys, String name, Runnable action) {
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keys), name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    /**
     * Writes a step off the Swing thread. {@code saved} receives the step as written, with what it actually replaced,
     * and {@code failed} runs when it could not be written. {@code conflict}, when given, runs instead of
     * {@code failed} when a text step finds other text in the file than it was made against.
     */
    private void write(Step step, boolean overwrite, Consumer<Step> saved, Runnable failed, Runnable conflict) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return switch (step) {
                    case SettingStep setting -> writeSetting(setting);
                    case TextStep text -> writeText(text, overwrite);
                };
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, WRITES).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                if (cause instanceof ConflictException && conflict != null) {
                    conflict.run();
                    return;
                }
                failed.run();
                this.status.accept(cause.getMessage());
                return;
            }
            saved.accept(result.step());
            this.status.accept(result.message());
            this.written.run();
        }));
    }

    private Saved writeSetting(SettingStep step) throws IOException {
        Target target = step.target();
        String key = target.setting().path();
        String previous = ConfigEdit.write(target.file(), key, step.after());
        ConfigChanges.Effect effect = this.changes.edited(
                new ChangeRecord.Setting(target.modId(), target.fileName(), target.file(), key), target.type(),
                target.setting().restart(), previous, step.after());
        return new Saved(new SettingStep(target, previous, step.after()),
                target.setting().name() + " saved, " + effect.description());
    }

    private Saved writeText(TextStep step, boolean overwrite) throws IOException {
        FileTarget target = step.target();
        String current = Files.readString(target.file(), StandardCharsets.UTF_8);
        if (!overwrite && !current.equals(step.before())) throw new ConflictException(target.file());
        List<ConfigEdit.TextChange> changed = ConfigEdit.changes(current, step.after(), step.settings());
        Files.writeString(target.file(), step.after(), StandardCharsets.UTF_8);
        List<ConfigChanges.Effect> effects = new ArrayList<>();
        for (ConfigEdit.TextChange change : changed) {
            effects.add(this.changes.edited(new ChangeRecord.Setting(target.modId(), target.fileName(), target.file(),
                    change.setting().path()), target.type(), change.setting().restart(), change.before(), change.after()));
        }
        return new Saved(new TextStep(target, step.settings(), current, step.after()), textMessage(target, changed, effects));
    }

    /**
     * What saving a text did: the one setting it changed and when the game uses it, or how many settings changed and
     * the one the game waits longest for.
     */
    private static String textMessage(FileTarget target, List<ConfigEdit.TextChange> changed,
                                      List<ConfigChanges.Effect> effects) {
        String fileName = target.fileName().substring(target.fileName().lastIndexOf('/') + 1);
        if (changed.isEmpty()) return fileName + " saved";
        if (changed.size() == 1) return changed.getFirst().setting().name() + " saved, " + effects.getFirst().description();
        int waiting = -1;
        for (int index = 0; index < effects.size(); index++) {
            ConfigChanges.Effect effect = effects.get(index);
            if (effect.pending() && (waiting < 0 || effect == ConfigChanges.Effect.RESTART)) waiting = index;
        }
        String saved = changed.size() + " settings saved";
        if (waiting >= 0) return saved + ", " + changed.get(waiting).setting().name() + " " + effects.get(waiting).description();
        return effects.stream().allMatch(effect -> effect == ConfigChanges.Effect.NOW) ? saved + ", the game reloaded them" : saved;
    }
}
