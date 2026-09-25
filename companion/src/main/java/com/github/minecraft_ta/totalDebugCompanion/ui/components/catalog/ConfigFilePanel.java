package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigValues;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.HierarchyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A mod's configuration file in its own tab, edited as text with the same checks as its Configuration tab. The file
 * is read again whenever the tab is shown, unless its text has unsaved changes.
 */
public final class ConfigFilePanel extends JPanel {
    private final Path file;
    private final ConfigTextEditor editor;
    private final JLabel notice = new JLabel();
    private long generation;

    /** {@code owner} is the mod configuration {@code file} holds, such as a world's copy of a server configuration. */
    public ConfigFilePanel(Path file, ConfigSources.Owner owner, ConfigChanges changes) {
        super(new BorderLayout());
        this.file = Objects.requireNonNull(file, "file");
        ConfigWriter writer = new ConfigWriter(changes, this::setStatus, this::load);
        this.editor = new ConfigTextEditor(writer, this::setStatus, this::load);
        this.editor.setDocument(new ConfigTextEditor.Document(new ConfigWriter.FileTarget(owner.mod().id(),
                owner.file().fileName(), file, owner.file().type()), owner.file().settings()));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(this.editor.saveButton());
        actions.add(this.editor.discardButton());
        ThemeColors.keepForeground(this.notice, ThemeColors::secondaryText);
        this.notice.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        bar.add(this.notice, BorderLayout.CENTER);
        bar.add(actions, BorderLayout.EAST);
        add(bar, BorderLayout.NORTH);
        add(this.editor.component(), BorderLayout.CENTER);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) load();
        });
        load();
    }

    public Path file() {
        return this.file;
    }

    /** Reads the file again; unsaved changes to its text stay. */
    public void load() {
        long current = ++this.generation;
        CompletableFuture.supplyAsync(() -> {
            try {
                long size = Files.size(this.file);
                if (size > ConfigValues.MAX_FILE_BYTES) {
                    throw new IOException(this.file.getFileName() + " has " + size + " bytes; the limit is " + ConfigValues.MAX_FILE_BYTES);
                }
                return Files.readString(this.file, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((text, failure) -> SwingUtilities.invokeLater(() -> {
            if (current != this.generation) return;
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                setStatus("Could not read " + this.file.getFileName() + ": " + cause.getMessage());
            } else {
                this.editor.load(text);
            }
        }));
    }

    private void setStatus(String status) {
        this.notice.setText(status);
    }

    /** Whether the tab can close: its text has no unsaved changes, or they were discarded after asking. */
    public boolean canClose() {
        return this.editor.confirmLeave();
    }

    /** Moves the caret to an offset of the text. */
    public void navigateToOffset(int offset) {
        this.editor.component().navigateToOffset(offset);
    }
}
