package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigEdit;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Writes configuration edits off the Swing thread, tells when the game uses each one, and keeps them for undo and
 * redo. Edits of several files share one history, so undo steps back through them in the order they were made.
 */
final class ConfigWriter {
    /** A setting in the file it is written to. */
    record Target(Path file, PackCatalog.ConfigType type, PackCatalog.ConfigSetting setting) {
        Target {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(setting, "setting");
        }
    }

    /** An edit written to a file, with the value it replaced. */
    private record Edit(Target target, String before, String after) {
        Edit reversed() {
            return new Edit(this.target, this.after, this.before);
        }
    }

    private record Saved(Edit edit, ConfigChanges.Effect effect) {
    }

    private final ConfigChanges changes;
    private final Consumer<String> status;
    private final Runnable written;
    private final Deque<Edit> undo = new ArrayDeque<>();
    private final Deque<Edit> redo = new ArrayDeque<>();

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

    /** The value a setting had before it was first edited while Companion runs, as the file wrote it, or null. */
    String original(Path file, String setting) {
        return this.changes.original(file, setting);
    }

    /** Drops pending rejoin edits whose world has closed. Blocking; not on the Swing thread. */
    void refreshPending() {
        this.changes.refresh();
    }

    /** Writes {@code after} in place of {@code before}, the value shown when the edit was made. */
    void edit(Target target, String before, String after) {
        write(new Edit(target, before, after), saved -> {
            this.undo.push(saved);
            this.redo.clear();
        });
    }

    void undo() {
        Edit edit = this.undo.poll();
        if (edit != null) write(edit.reversed(), saved -> this.redo.push(edit));
    }

    void redo() {
        Edit edit = this.redo.poll();
        if (edit != null) write(edit, this.undo::push);
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

    /** {@code saved} receives the edit as written, with the value it actually replaced. */
    private void write(Edit edit, Consumer<Edit> saved) {
        Target target = edit.target();
        String key = target.setting().path();
        CompletableFuture.supplyAsync(() -> {
            try {
                String previous = ConfigEdit.write(target.file(), key, edit.after());
                ConfigChanges.Effect effect = this.changes.edited(target.file(), target.type(), key,
                        target.setting().restart(), previous, edit.after());
                return new Saved(new Edit(target, previous, edit.after()), effect);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                this.status.accept(cause.getMessage());
                return;
            }
            saved.accept(result.edit());
            this.status.accept(target.setting().name() + " saved, " + result.effect().description());
            this.written.run();
        }));
    }
}
