package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSettings;
import com.github.minecraft_ta.totalDebugCompanion.change.Effect;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Makes a view's configuration edits through {@link ConfigSettings}, shows how each went, and keeps them for undo and
 * redo. Edits of several files share one history, so undo steps back through them in the order they were made; a saved
 * text is one step. Undoing is a change back to the value before, which expects the value the step wrote, so it leaves
 * a setting changed elsewhere since alone and says so.
 */
final class ConfigWriter {
    /** A step of the history: one setting's value, or a file's whole text, with what it replaced. */
    private sealed interface Step permits SettingStep, TextStep {
        Step reversed();
    }

    private record SettingStep(ConfigSettings.Target target, String before, String after) implements Step {
        @Override
        public Step reversed() {
            return new SettingStep(this.target, this.after, this.before);
        }
    }

    private record TextStep(ConfigSettings.FileTarget target, List<PackCatalog.ConfigSetting> settings, String before, String after)
            implements Step {
        @Override
        public Step reversed() {
            return new TextStep(this.target, this.settings, this.after, this.before);
        }
    }

    private final ConfigSettings settings;
    private final Consumer<String> status;
    private final Runnable written;
    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();

    /** {@code status} shows the outcome of each write, and {@code written} runs after a write succeeded. */
    ConfigWriter(ConfigSettings settings, Consumer<String> status, Runnable written) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.status = Objects.requireNonNull(status, "status");
        this.written = Objects.requireNonNull(written, "written");
    }

    /** What the running game waits for before it uses the edited value of a setting, or null. */
    Effect pending(Path file, String setting) {
        return this.settings.pending(file, setting);
    }

    /** The value a setting had before Companion first changed it, as the file wrote it, or null. */
    String original(Path file, String setting) {
        return this.settings.original(file, setting);
    }

    /** Drops pending rejoin edits whose world has closed. Blocking; not on the Swing thread. */
    void refreshPending() {
        this.settings.refresh();
    }

    /** Writes {@code after} in place of {@code before}, the value shown when the edit was made. */
    void edit(ConfigSettings.Target target, String before, String after) {
        write(new SettingStep(target, before, after), false, this::done, () -> { }, null);
    }

    /**
     * Writes {@code after} in place of {@code before}, the value Companion last wrote, as one of several reverts: shows
     * nothing itself, and completes with why the setting kept its value, or empty.
     */
    CompletableFuture<String> revert(ConfigSettings.Target target, String before, String after) {
        SettingStep step = new SettingStep(target, before, after);
        return this.settings.set(target, before, after).handle((saved, failure) -> {
            if (failure == null) {
                SwingUtilities.invokeLater(() -> {
                    done(step);
                    this.written.run();
                });
                return "";
            }
            return target.setting().name() + ": " + cause(failure).getMessage();
        });
    }

    /**
     * Writes a file's edited text in place of {@code before}, the text it was edited from. When the file holds other
     * text by now, {@code conflict} runs instead unless {@code overwrite}; {@code saved} runs after the write.
     */
    void saveText(ConfigSettings.FileTarget target, List<PackCatalog.ConfigSetting> settings, String before, String after,
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
     * Makes a step's change. {@code saved} receives the step, and {@code failed} runs when it was not made. A setting's step
     * expects the value it replaces, so one changed elsewhere since is refused. {@code conflict}, when given, runs instead
     * of {@code failed} when a text step finds other text in the file than it was made against.
     */
    private void write(Step step, boolean overwrite, Consumer<Step> saved, Runnable failed, Runnable conflict) {
        CompletableFuture<ConfigSettings.Saved> change = switch (step) {
            case SettingStep setting -> this.settings.set(setting.target(), setting.before(), setting.after());
            case TextStep text -> this.settings.saveText(text.target(), text.settings(), text.before(), text.after(), overwrite);
        };
        change.whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure != null) {
                Throwable cause = cause(failure);
                if (cause instanceof ConfigSettings.ConflictException && conflict != null) {
                    conflict.run();
                    return;
                }
                failed.run();
                this.status.accept(cause.getMessage());
                return;
            }
            // An overwritten text is undone to what the file held, not to the text the edit was opened from.
            saved.accept(step instanceof TextStep text && result.replaced() != null
                    ? new TextStep(text.target(), text.settings(), result.replaced(), text.after()) : step);
            this.status.accept(result.message());
            this.written.run();
        }));
    }

    private static Throwable cause(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }
}
