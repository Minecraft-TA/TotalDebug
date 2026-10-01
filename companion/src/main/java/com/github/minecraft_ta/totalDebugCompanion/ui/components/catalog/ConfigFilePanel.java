package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSettings;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationViewState;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PageLoader;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigValues;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * A mod's configuration file in its own tab, edited as text with the same checks as its Configuration tab. It opens
 * with the text {@link #read} read; the file is read again whenever the tab is shown, and the text shown changes only
 * where it has no unsaved changes and the file holds other text.
 */
public final class ConfigFilePanel extends JPanel {
    private final Path file;
    private final ConfigTextEditor editor;
    private final JLabel notice = new JLabel();
    private final PageLoader<String> loader;

    /** {@code owner} is the mod configuration {@code file} holds, such as a world's copy of a server configuration. */
    public ConfigFilePanel(Path file, ConfigSources.Owner owner, ConfigSettings configSettings, String text) {
        super(new BorderLayout());
        this.file = Objects.requireNonNull(file, "file");
        ConfigWriter writer = new ConfigWriter(configSettings, this::setStatus, this::load);
        this.editor = new ConfigTextEditor(writer, this::setStatus, this::load);
        this.editor.setDocument(new ConfigTextEditor.Document(new ConfigSettings.FileTarget(owner.mod().id(),
                owner.file().fileName(), file, owner.file().type()), owner.file().settings()));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(this.editor.saveButton());
        actions.add(this.editor.discardButton());
        ThemeColors.keepForeground(this.notice, ThemeColors::secondaryText);
        this.notice.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBorder(UiMetrics.barPadding());
        bar.add(this.notice, BorderLayout.CENTER);
        bar.add(actions, BorderLayout.EAST);
        add(bar, BorderLayout.NORTH);
        add(this.editor.component(), BorderLayout.CENTER);
        // The first read, when the tab is first shown, finds this text, which leaves the text and its caret alone.
        this.editor.load(text);
        this.loader = new PageLoader<>(() -> this::readFile, this.editor::load,
                failure -> setStatus("Could not read " + this.file.getFileName() + ": " + failure.getMessage())).page(this).readsWhenShown(this);
    }

    public Path file() {
        return this.file;
    }

    /** Reads the file again; unsaved changes to its text stay. */
    public void load() {
        this.loader.load();
    }

    private String readFile() throws IOException {
        return read(this.file);
    }

    /** Reads a configuration file to open it or show it again, on file work. */
    public static String read(Path file) throws IOException {
        long size = Files.size(file);
        if (size > ConfigValues.MAX_FILE_BYTES) {
            throw new IOException(file.getFileName() + " has " + size + " bytes; the limit is " + ConfigValues.MAX_FILE_BYTES);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private void setStatus(String status) {
        this.notice.setText(status);
    }

    /** Whether the tab can close: its text has no unsaved changes, or they were discarded after asking. */
    public boolean canClose() {
        return this.editor.confirmLeave();
    }

    /** Moves the caret to an offset of the text shown, if {@code stillWanted} still holds then. */
    public CompletableFuture<Void> navigateToOffset(int offset, BooleanSupplier stillWanted) {
        return this.editor.component().navigateToOffset(offset, stillWanted);
    }

    public NavigationViewState captureNavigationViewState() {
        return this.editor.component().captureNavigationViewState();
    }

    public void restoreNavigationViewState(NavigationViewState state) {
        this.editor.component().restoreNavigationViewState(state);
    }

    /** Stops reading, as when the tab closed. */
    public void dispose() {
        this.loader.dispose();
    }
}
