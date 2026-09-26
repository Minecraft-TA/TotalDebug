package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourcePaths;
import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A text resource of the pack, edited in place. It shows the copy the game uses: the managed pack's, or the file that
 * was opened. Save (Ctrl+S) checks the text, writes it into the pack Companion manages and reloads what the game needs;
 * the bar tells where the text comes from, when the game uses it and what its reload reported. The Changes page
 * reverts a saved edit, and the editor follows it.
 */
final class ResourceTextEditor extends JPanel {
    private static final Pattern LINE = Pattern.compile("line (\\d+)");

    /**
     * The managed pack the text is saved in, its entry in the change record when the copy was read, its copy there or
     * null, and a pack above it that supplies the file too, or null.
     */
    private record Found(Path pack, ChangeRecord.Change recorded, String managed, String overriddenBy) {
    }

    private final String path;
    private final String origin;
    private final ResourceEdits edits;
    private final EditableTextPanel text;
    private final JLabel state = new JLabel();
    private final JLabel notice = new JLabel();
    private final JButton save = new JButton("Save", Icons.SAVE);
    private final JButton discard = new JButton("Discard", Icons.REVERT);
    private Supplier<Color> noticeColor = ThemeColors::secondaryText;
    /** The text of the file that was opened, which the pack supplies while the managed pack holds no copy. */
    private final String openedText;
    /** Stops following the change record. */
    private final Runnable stopListening;
    /** Stops following the game's packs. */
    private final Runnable stopFollowingPacks;
    /** The text a save under way writes, or null. */
    private String saving;
    /** The managed pack the opened file lies in, which stays the target, or null to save into the current world's. */
    private final Path opened;
    /** The text the pack supplies: the managed pack's copy, or the opened file's. */
    private String packText;
    /** This resource's entry in the change record when the copies were last read, or null. */
    private ChangeRecord.Change seen;
    /** The managed pack the shown copy was read from, or null until it is read. */
    private Path pack;
    /** The managed pack the text is saved in, as the bar names it. */
    private String packName = "the TotalDebug pack";
    /** Counts writes, so the copies read when the tab opened never replace the text of a later write. */
    private int writes;
    /** Counts reads of the copies; only the latest may show its result, since reads can finish out of order. */
    private int reads;
    private boolean managed;
    private boolean busy;
    private boolean disposed;

    /**
     * {@code origin} names the file that was opened, such as a mod's JAR, and {@code pack} is the managed pack it lies in,
     * which stays the target, or null to save into the current world's.
     */
    ResourceTextEditor(String path, String origin, Path pack, LoadedResource.Text content, ResourceEdits edits) {
        super(new BorderLayout());
        this.path = Objects.requireNonNull(path, "path");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.opened = pack;
        this.pack = pack;
        this.edits = Objects.requireNonNull(edits, "edits");
        this.openedText = content.value();
        this.packText = content.value();
        this.text = new EditableTextPanel(content.syntaxStyle(), this::changed, this::save);
        this.text.load(content.value());

        this.save.setToolTipText(Tooltip.action("Save", "Ctrl+S").text(path.startsWith("assets/")
                ? "Checks the text, writes it into the TotalDebug resource pack and reloads it in the game"
                : "Checks the text, writes it into the TotalDebug datapack of the current world and reloads it in the game").html());
        this.save.addActionListener(event -> save());
        this.discard.setToolTipText(Tooltip.action("Discard", null).text("Drops the unsaved changes to the text").html());
        this.discard.addActionListener(event -> discard());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(this.save);
        actions.add(this.discard);
        ThemeColors.keepForeground(this.state, ThemeColors::secondaryText);
        this.state.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBorder(UiMetrics.barPadding());
        bar.add(this.state, BorderLayout.CENTER);
        bar.add(actions, BorderLayout.EAST);
        ThemeColors.keepForeground(this.notice, () -> this.noticeColor.get());
        this.notice.setBorder(UiMetrics.noticePadding());
        this.notice.setVisible(false);
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        top.add(this.notice, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(this.text, BorderLayout.CENTER);
        showState("");
        changed();
        this.seen = recorded();
        readCopies(false);
        // A revert on the Changes page changes what the game uses.
        this.stopListening = edits.record().addListener(() -> SwingUtilities.invokeLater(this::recordChanged));
        // Another world opening changes the current world's datapack a data file is shown from and saved into.
        this.stopFollowingPacks = edits.addStackListener(() -> SwingUtilities.invokeLater(this::packsChanged));
    }

    /** Reads the copies again when the tab follows the current world, which may have changed. */
    private void packsChanged() {
        if (!this.disposed && this.opened == null && !this.busy) readCopies(false);
    }

    /** Reads the copies again when this resource's entry in the change record changed. */
    private void recordChanged() {
        if (this.disposed) return;
        ChangeRecord.Change now = recorded();
        if (Objects.equals(now, this.seen)) return;
        this.seen = now;
        // A write of this editor shows its own result when it completes.
        if (!this.busy) readCopies(true);
    }

    /** This resource's entry in the change record for its managed pack, or null while it has none or the pack is not known. */
    private ChangeRecord.Change recorded() {
        return this.pack == null ? null : this.edits.record().change(new ChangeRecord.Resource(this.path, this.pack));
    }

    EditableTextPanel textPanel() {
        return this.text;
    }

    /**
     * Shows the managed pack's copy instead of the opened file's, and names a pack that overrides the managed one.
     * {@code changed} tells that the file itself changed, such as by a revert, which ends what the notice said about it.
     */
    private void readCopies(boolean changed) {
        int started = this.writes;
        int read = ++this.reads;
        CompletableFuture.supplyAsync(() -> {
            try {
                // Without an opened pack, the current world's is looked up each time: the player can open another world.
                Path pack = this.opened != null ? this.opened : this.edits.pack(this.path);
                // The record is looked at before the file, so a change between the two is caught afterwards.
                ChangeRecord.Change recorded = this.edits.record().change(new ChangeRecord.Resource(this.path, pack));
                return new Found(pack, recorded, this.edits.managed(pack, this.path).map(ResourceTextEditor::text).orElse(null),
                        this.edits.overriddenBy(this.path).orElse(null));
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((found, failure) -> SwingUtilities.invokeLater(() -> {
            if (this.disposed || this.writes != started || this.reads != read) return;
            if (failure != null) {
                showNotice(message(failure), ThemeColors::error);
                return;
            }
            showPack(found.pack());
            this.seen = found.recorded();
            if (changed) showNotice("", ThemeColors::secondaryText);
            // Without a managed copy, such as after a revert, the pack supplies the opened file's text again, unless the
            // opened file was the managed copy: then nothing is left, and the shown text is unsaved.
            this.managed = found.managed() != null;
            this.packText = this.managed ? found.managed() : this.opened != null ? "" : this.openedText;
            // A deleted file keeps its text on screen, as unsaved text a Save would write again.
            if (!this.text.modified() && (this.managed || this.opened == null)) this.text.load(this.packText);
            else this.text.markSaved(this.packText);
            showState("");
            if (found.overriddenBy() != null) {
                showNotice(found.overriddenBy() + " is above the TotalDebug pack and supplies this file too, so the game shows its copy",
                        ThemeColors::warning);
            }
            changed();
            // A save or revert that came after the record was looked at is read again.
            if (!Objects.equals(recorded(), this.seen)) readCopies(true);
        }));
    }

    /** Names the managed pack the shown text belongs to, and follows its entry in the change record. */
    private void showPack(Path pack) {
        this.pack = pack;
        this.packName = packName(pack);
        this.seen = recorded();
        this.save.setToolTipText(Tooltip.action("Save", "Ctrl+S")
                .text("Checks the text, writes it into " + this.packName + " and reloads it in the game").html());
    }

    /** A managed pack as the bar names it: the resource pack, or the datapack of a world by its folder. */
    private static String packName(Path pack) {
        Path datapacks = pack.getParent();
        if (datapacks == null || !datapacks.getFileName().toString().equals("datapacks") || datapacks.getParent() == null) {
            return "the TotalDebug pack";
        }
        return "the TotalDebug datapack of " + datapacks.getParent().getFileName();
    }

    private static String text(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }

    private void changed() {
        boolean edited = !this.text.text().equals(this.packText);
        this.save.setVisible(edited);
        this.discard.setVisible(edited);
        this.save.setEnabled(!this.busy);
        this.discard.setEnabled(!this.busy);
    }

    /** Names where the text comes from, followed by {@code detail} when it is not empty. */
    private void showState(String detail) {
        String source = this.managed ? "Edited in " + this.packName
                : this.opened != null ? "Not in " + this.packName + " any more" : "From " + this.origin;
        this.state.setText(detail.isEmpty() ? source : source + ", " + detail);
    }

    /** The checked text, or null after showing why it cannot be used. */
    private String checked() {
        String edited = this.text.text();
        Optional<String> problem = ResourcePaths.check(this.path, edited);
        if (problem.isEmpty()) return edited;
        showNotice(problem.get(), ThemeColors::error);
        Matcher line = LINE.matcher(problem.get());
        if (line.find()) this.text.goToLine(Integer.parseInt(line.group(1)));
        return null;
    }

    /** Checks the text, writes it into the managed pack, then shows what the game uses and what its reload reported. */
    void save() {
        if (this.busy || this.text.text().equals(this.packText)) return;
        String edited = checked();
        if (edited == null) return;
        this.writes++;
        this.busy = true;
        changed();
        this.state.setText("Reloading in the game");
        this.saving = edited;
        this.edits.save(this.path, this.opened != null ? this.opened : this.pack, edited.getBytes(StandardCharsets.UTF_8))
                .whenComplete((saved, failure) -> SwingUtilities.invokeLater(() -> {
                    this.busy = false;
                    this.saving = null;
                    if (this.disposed) return;
                    if (failure != null) {
                        showState("");
                        showNotice("Not saved: " + message(failure), ThemeColors::error);
                        changed();
                        return;
                    }
                    boolean keepEdits = !this.text.text().equals(edited);
                    showPack(saved.pack());
                    this.packText = edited;
                    this.managed = true;
                    if (keepEdits) this.text.markSaved(edited);
                    else this.text.load(edited);
                    showState(saved.reloadFailure().isEmpty() ? saved.effect().description() : "");
                    showResult(saved.problems(), saved.reloadFailure());
                    changed();
                }));
    }

    private void showResult(List<String> problems, String reloadFailure) {
        if (!reloadFailure.isEmpty()) {
            showNotice("The game did not reload it: " + reloadFailure, ThemeColors::error);
        } else if (!problems.isEmpty()) {
            String first = problems.getFirst();
            showNotice(problems.size() == 1 ? "The game reported: " + first
                    : "The game reported " + problems.size() + " problems, first: " + first, ThemeColors::warning);
            this.notice.setToolTipText(Tooltip.of("Problems").text(String.join("\n", problems)).html());
        } else {
            showNotice("", ThemeColors::secondaryText);
        }
    }

    /** Drops the unsaved changes, back to the pack's text. */
    void discard() {
        this.text.load(this.packText);
        showNotice("", ThemeColors::secondaryText);
        changed();
    }

    /** Whether the text can be left: it has no unsaved changes, or they were discarded after asking. */
    boolean confirmLeave() {
        // A save under way is written whether or not the tab stays open.
        if (this.text.text().equals(this.packText) || this.text.text().equals(this.saving)) return true;
        String name = this.path.substring(this.path.lastIndexOf('/') + 1);
        Object[] options = {"Discard", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, "The text of " + name + " has unsaved changes.",
                "Unsaved changes", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (choice != 0) return false;
        discard();
        return true;
    }

    private void showNotice(String message, Supplier<Color> color) {
        this.noticeColor = color;
        this.notice.setForeground(color.get());
        this.notice.setText(message);
        this.notice.setToolTipText(null);
        this.notice.setVisible(!message.isEmpty());
    }

    private static String message(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    void dispose() {
        this.disposed = true;
        this.stopListening.run();
        this.stopFollowingPacks.run();
        this.text.dispose();
    }
}
