package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigEdit;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigValues;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.EditableTextPanel;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import java.nio.charset.StandardCharsets;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A mod's configuration file edited as text. Save (Ctrl+S) checks the text the way NeoForge checks the file on load
 * and writes it; a text NeoForge would correct is refused with its first problem, and a file changed on disk since it
 * was opened is overwritten or reloaded only after asking. Discard drops unsaved changes. Saved changes are recorded
 * like edits made in the settings table.
 */
final class ConfigTextEditor {
    private static final Pattern LINE = Pattern.compile("line (\\d+)");

    /** The file shown: where it is written and the settings its specification declares, empty without one. */
    record Document(ConfigWriter.FileTarget target, List<PackCatalog.ConfigSetting> settings) {
        Document {
            Objects.requireNonNull(target, "target");
            settings = List.copyOf(settings);
        }
    }

    private final ConfigWriter writer;
    private final Consumer<String> status;
    private final Runnable reload;
    private final EditableTextPanel text;
    private final JButton save = new JButton("Save", Icons.SAVE);
    private final JButton discard = new JButton("Discard", Icons.REVERT);
    private Document document;

    /** {@code status} shows the outcome of a save, and {@code reload} reads the file again. */
    ConfigTextEditor(ConfigWriter writer, Consumer<String> status, Runnable reload) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.status = Objects.requireNonNull(status, "status");
        this.reload = Objects.requireNonNull(reload, "reload");
        this.text = new EditableTextPanel(FileTypeResolver.SYNTAX_STYLE_TOML, this::changed, () -> save(false));
        this.save.setToolTipText(Tooltip.action("Save", "Ctrl+S").text("Checks the text and writes it to the file").html());
        this.save.addActionListener(event -> save(false));
        this.discard.setToolTipText("Drop the unsaved changes to the text");
        this.discard.addActionListener(event -> discard());
        changed();
    }

    EditableTextPanel component() {
        return this.text;
    }

    JButton saveButton() {
        return this.save;
    }

    JButton discardButton() {
        return this.discard;
    }

    /** Sets the file shown; null when there is none to write. */
    /**
     * Sets the file the text is saved to. Modified text stays with the file it was edited from until it is saved or
     * discarded, so a refresh that selects another file or world never saves it there.
     */
    void setDocument(Document document) {
        if (this.text.modified() && this.document != null
                && (document == null || !document.target().file().equals(this.document.target().file()))) {
            return;
        }
        this.document = document;
    }

    /** Shows the file's text as read from disk, unless the shown text has unsaved changes. */
    void load(String fileText) {
        if (!this.text.modified()) this.text.load(fileText);
    }

    boolean modified() {
        return this.text.modified();
    }

    private void changed() {
        boolean modified = this.text.modified();
        this.save.setVisible(modified);
        this.discard.setVisible(modified);
    }

    /**
     * Checks the edited text and writes it. When the file changed on disk since it was opened, asks whether to
     * overwrite it or reload it unless {@code overwrite}.
     */
    void save(boolean overwrite) {
        Document shown = this.document;
        if (shown == null || !this.text.modified()) return;
        String edited = this.text.text();
        if (edited.getBytes(StandardCharsets.UTF_8).length > ConfigValues.MAX_FILE_BYTES) {
            // A larger file could not be read again here.
            this.status.accept("The text is larger than " + ConfigValues.MAX_FILE_BYTES / 1024 / 1024 + " MB");
            return;
        }
        try {
            ConfigEdit.checkText(this.text.savedText(), edited, shown.settings());
        } catch (IllegalArgumentException problem) {
            this.status.accept(problem.getMessage());
            Matcher line = LINE.matcher(problem.getMessage());
            if (line.find()) this.text.goToLine(Integer.parseInt(line.group(1)));
            return;
        }
        this.writer.saveText(shown.target(), shown.settings(), this.text.savedText(), edited, overwrite, () -> {
            this.text.markSaved(edited);
            changed();
        }, () -> resolveConflict(shown));
    }

    private void resolveConflict(Document shown) {
        Object[] options = {"Overwrite", "Reload", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this.text, shown.target().file().getFileName()
                        + " changed on disk since it was opened.", "File changed", JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[2]);
        if (choice == 0) save(true);
        else if (choice == 1) discard();
    }

    /** Drops the unsaved changes and reads the file again. */
    void discard() {
        this.text.load(this.text.savedText());
        changed();
        this.status.accept("");
        this.reload.run();
    }

    /** Whether the text can be left: it has no unsaved changes, or they were discarded after asking. */
    boolean confirmLeave() {
        if (!this.text.modified()) return true;
        String name = this.document == null ? "the file" : this.document.target().file().getFileName().toString();
        Object[] options = {"Discard", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this.text, "The text of " + name + " has unsaved changes.",
                "Unsaved changes", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (choice != 0) return false;
        this.text.load(this.text.savedText());
        changed();
        return true;
    }
}
