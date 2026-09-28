package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.catalog.PackFolders;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A resource of the pack, edited in place: the text or texture shown by a subclass, and the bar that saves it. A file
 * opened from a folder pack is saved in that pack; a file of a mod or another archive is saved into the working pack of
 * its side, chosen under Save into: the pack Companion manages, or a folder pack of the player's own. The editor shows
 * the copy the game uses: the target pack's, or the file that was opened. Save (Ctrl+S) checks the content, writes it
 * and reloads what the game needs; the bar tells where the content comes from, when the game uses it and what its
 * reload reported. The Changes page reverts a saved edit, and the editor follows it.
 *
 * @param <V> the content as the editor holds it, such as the text
 */
abstract class PackResourceEditor<V> extends JPanel {
    /**
     * The pack the content is saved in, its entry in the change record when the copy was read, its copy there or null and
     * the hash of that copy's bytes, empty for none, why the game does not use that copy or null, and the packs it could be
     * saved into instead, empty for an opened pack.
     */
    private record Found<V>(Path pack, ChangeRecord.Change recorded, V managed, String hash, String unused, List<Path> targets) {
    }

    private final String path;
    private final String origin;
    private final ResourceEdits edits;
    private final JLabel state = new JLabel();
    private final JLabel notice = new JLabel();
    private final JButton save = new JButton("Save", Icons.SAVE);
    private final JButton discard = new JButton("Discard", Icons.REVERT);
    private final JLabel targetLabel = new JLabel("Save into");
    private final JComboBox<Path> target = new JComboBox<>();
    /** Set while the target list is filled, so that filling it does not count as a choice. */
    private boolean listingTargets;
    private Supplier<Color> noticeColor = ThemeColors::secondaryText;
    /** The content of the file that was opened, which the pack supplies while the managed pack holds no copy. */
    private final V openedContent;
    /** Stops following the change record. */
    private Runnable stopListening = () -> { };
    /** Stops following the game's packs. */
    private Runnable stopFollowingPacks = () -> { };
    /** Stops following the choice of working pack. */
    private Runnable stopFollowingWorkingPack = () -> { };
    /** The content a save under way writes, or null. */
    private V saving;
    /** The folder pack the opened file lies in, which stays the target, or null to save into the working pack. */
    private final Path opened;
    /** The content the pack supplies: the managed pack's copy, or the opened file's; {@link #none()} for no copy. */
    private V packContent;
    /** This resource's entry in the change record when the copies were last read, or null. */
    private ChangeRecord.Change seen;
    /** The pack the shown copy was read from, or null until it is read. */
    private Path pack;
    /** The pack the content is saved in, as the bar names it. */
    private String packName = "the working pack";
    /** Counts writes, so the copies read when the tab opened never replace the content of a later write. */
    private int writes;
    /** Counts reads of the copies; only the latest may show its result, since reads can finish out of order. */
    private int reads;
    private boolean managed;
    private boolean busy;
    /**
     * The working pack or the current world changed and its copy is being read; a save waits for it, so it goes into the
     * pack the tab shows.
     */
    private boolean following;
    /** The working pack or the current world changed during a save, and is followed once the save completes. */
    private boolean followAfterSave;
    /** Whether that change ends what the notice said, as a new working pack does; a pack stack change does not. */
    private boolean clearAfterSave;
    /**
     * What a read of the copies last put in the notice, such as why the game does not use the shown copy, or null. The
     * next read replaces it; a save's result stays.
     */
    private String readNotice;
    /**
     * The hash of the pack's copy this tab last loaded or saved, empty when the pack held none, or null before the first
     * read: a save replaces only that copy, and asks before replacing another one written since, such as by another tab.
     */
    private String baseline;
    /** The pack the baseline belongs to; moving to another pack starts from that pack's copy. */
    private Path baselinePack;
    /** The hash of {@code packContent}, the copy Discard goes back to, which becomes the baseline then. */
    private String packHash;
    /** Asks whether to save over a copy written since this tab read the pack's; tests answer it themselves. */
    Predicate<ResourceEdits.ChangedSince> askToReplace = this::replaceChangedCopy;
    private boolean disposed;

    /**
     * {@code origin} names the file that was opened, such as a mod's JAR, and {@code pack} is the folder pack it lies in,
     * which stays the target, or null to save into the working pack of its side. {@code content} is what was opened.
     */
    PackResourceEditor(String path, String origin, Path pack, V content, ResourceEdits edits) {
        super(new BorderLayout());
        this.path = Objects.requireNonNull(path, "path");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.opened = pack;
        this.pack = pack;
        this.edits = Objects.requireNonNull(edits, "edits");
        this.openedContent = content;
        this.packContent = content;
    }

    protected final ResourceEdits edits() {
        return this.edits;
    }

    /** The resource's pack path, such as {@code assets/ns/lang/en_us.json}. */
    protected final String path() {
        return this.path;
    }

    /** What the view shows now. */
    protected abstract V shown();

    /** Whether two contents are the same for the game. */
    protected abstract boolean same(V first, V second);

    /** Shows {@code content} as the saved content, where the view keeps its place as far as it can. */
    protected abstract void load(V content);

    /** Takes {@code content} as saved, keeping what the view shows. */
    protected abstract void markSaved(V content);

    /** Whether the view differs from what it last loaded or saved. */
    protected abstract boolean modified();

    /** The content of a file's bytes. Blocking. */
    protected abstract V decode(byte[] bytes) throws IOException;

    /** The content of {@code pack}'s copy, whose bytes are {@code bytes}; what lies beside it may count too. Blocking. */
    protected V decode(byte[] bytes, Path pack) throws IOException {
        return decode(bytes);
    }

    /** The bytes the content is written as. */
    protected abstract byte[] encode(V content) throws IOException;

    /** The content when the pack holds no copy and the opened file cannot supply one: then everything shown is unsaved. */
    protected abstract V none();

    /** What the content is called in messages, such as {@code text} or {@code texture}. */
    protected abstract String noun();

    /** A copy of the shown content that later edits of the view leave alone; content that never changes in place is its own. */
    protected V copy(V content) {
        return content;
    }

    /**
     * Files the resource needs beside it in the pack it is saved into, by pack path, written only where that pack has
     * none, such as a texture's animation; none by default.
     */
    protected Map<String, byte[]> alongside() {
        return Map.of();
    }

    /** Lets the view be edited, or keeps it as it is while the pack's copy is not read yet. */
    protected abstract void setEditable(boolean editable);

    /** Why the content cannot be written, after showing where the problem is; empty when it can. */
    protected Optional<String> check(V content) {
        return Optional.empty();
    }

    /** Lays the editor out around {@code view}, then reads the copies and follows what changes them. */
    protected final void start(JComponent view) {
        this.save.setToolTipText(saveTooltip());
        this.save.addActionListener(event -> save());
        this.discard.setToolTipText(Tooltip.action("Discard", null).text("Drops the unsaved changes to the " + noun()).html());
        this.discard.addActionListener(event -> discard());
        this.target.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof Path pack) setText(PackFolders.title(pack));
                return this;
            }
        });
        this.target.setToolTipText(Tooltip.of("Save into").text("The pack a file of a mod is saved into, the TotalDebug pack or one of your own").html());
        this.target.addActionListener(event -> chooseTarget());
        this.targetLabel.setVisible(false);
        this.target.setVisible(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(this.targetLabel);
        actions.add(this.target);
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
        add(view, BorderLayout.CENTER);
        showState("");
        // Until the pack's copy is read, an edit would be made to the opened file's content and saved over that copy.
        setEditable(false);
        this.following = true;
        changed();
        this.seen = recorded();
        readCopies(false);
        // A revert on the Changes page changes what the game uses.
        this.stopListening = this.edits.record().addListener(() -> SwingUtilities.invokeLater(this::recordChanged));
        // Another world opening changes the current world's datapack a data file is shown from and saved into.
        this.stopFollowingPacks = this.edits.addStackListener(() -> SwingUtilities.invokeLater(this::packsChanged));
        // A working pack chosen in another tab is where this one saves too.
        this.stopFollowingWorkingPack = this.edits.addWorkingPackListener(side -> {
            if (side.equals(ResourceEdits.side(this.path))) SwingUtilities.invokeLater(this::targetChanged);
        });
    }

    private String saveTooltip() {
        return Tooltip.action("Save", "Ctrl+S").text("Checks the " + noun() + ", writes it into " + this.packName
                + " and reloads it in the game").html();
    }

    /**
     * Saves into the chosen pack from now on, for this file and every file of a mod on its side; every open tab, this one
     * too, then shows the new pack's copy.
     */
    private void chooseTarget() {
        if (this.listingTargets || !(this.target.getSelectedItem() instanceof Path chosen)) return;
        // After a pack whose copy could not be read, choosing the one shown before goes back to it.
        if (chosen.equals(this.pack) && !this.following) return;
        this.following = true;
        setEditable(false);
        changed();
        this.edits.setWorkingPack(this.path, chosen);
    }

    /** Shows the copy of the working pack chosen now, whose notice replaces the last pack's. */
    private void targetChanged() {
        follow(true);
    }

    /** Reads the copies again when the tab follows the current world, which may have changed. */
    private void packsChanged() {
        follow(false);
    }

    /** Reads the working pack's copy again, once a save under way completes; {@code changed} as for {@link #readCopies}. */
    private void follow(boolean changed) {
        if (this.disposed || this.opened != null) return;
        if (this.busy) {
            this.followAfterSave = true;
            this.clearAfterSave |= changed;
            return;
        }
        this.following = true;
        // A new working pack's copy is read before anything is edited, as when the tab opened. A pack stack change, which
        // comes after every reload and rarely changes the pack, leaves typing and drawing alone.
        if (changed) setEditable(false);
        changed();
        readCopies(changed);
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

    JComboBox<Path> targetBox() {
        return this.target;
    }

    String noticeText() {
        return this.notice.getText();
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
                // Without an opened pack, the working pack is looked up each time: the player can open another world or
                // choose another pack.
                Path pack = this.opened != null ? this.opened : this.edits.pack(this.path);
                // The record is looked at before the file, so a change between the two is caught afterwards.
                ChangeRecord.Change recorded = this.edits.record().change(new ChangeRecord.Resource(this.path, pack));
                Optional<byte[]> copy = this.edits.managed(pack, this.path);
                return new Found<>(pack, recorded, copy.isPresent() ? decode(copy.get(), pack) : null,
                        ResourceOriginals.hash(copy.orElse(null)),
                        this.edits.unusedBecause(this.path, pack).orElse(null),
                        this.opened != null ? List.of() : this.edits.packs(this.path));
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((found, failure) -> SwingUtilities.invokeLater(() -> {
            if (this.disposed || this.writes != started || this.reads != read) return;
            // A read that fails leaves the pack to save into unknown, so saving waits for the next one.
            if (failure != null) {
                this.readNotice = message(failure);
                showNotice(this.readNotice, ThemeColors::error);
                return;
            }
            this.following = false;
            setEditable(true);
            showPack(found.pack());
            showTargets(found.targets(), found.pack());
            this.seen = found.recorded();
            if (changed) showNotice("", ThemeColors::secondaryText);
            // Without a managed copy, such as after a revert, the pack supplies the opened file's content again, unless
            // the opened file was the managed copy: then nothing is left, and the shown content is unsaved.
            this.managed = found.managed() != null;
            this.packContent = this.managed ? found.managed() : this.opened != null ? none() : this.openedContent;
            // A deleted file keeps its content on screen, as unsaved content a Save would write again.
            if (!modified() && (this.managed || this.opened == null)) load(this.packContent);
            else markSaved(this.packContent);
            // Changes that match the copy read now, such as another tab's save of the same text, are unsaved no more.
            boolean unsaved = modified();
            // Unsaved changes keep the copy they were made to as the one a save replaces, so replacing another asks. A tab
            // moved to another pack, such as a newly chosen working pack, carries its changes over to that pack's copy.
            boolean samePack = found.pack().equals(this.baselinePack);
            boolean changedSince = unsaved && samePack && this.baseline != null && !this.baseline.equals(found.hash());
            if (!unsaved || this.baseline == null || !samePack) {
                this.baseline = found.hash();
                this.baselinePack = found.pack();
            }
            this.packHash = found.hash();
            showState("");
            // Why the game did not use the copy ends when it does now, such as after the player enabled the pack.
            boolean replaceable = this.notice.getText().isEmpty() || this.notice.getText().equals(this.readNotice);
            if (replaceable) showNotice("", ThemeColors::secondaryText);
            this.readNotice = found.unused();
            // A save's reload failure or problems stay: they tell why, such as a pack the game turned off after a failure.
            if (found.unused() != null && replaceable) showNotice(found.unused(), ThemeColors::warning);
            if (changedSince && replaceable) {
                this.readNotice = "The " + noun() + " changed in the pack since this tab read it; saving asks before replacing it";
                showNotice(this.readNotice, ThemeColors::warning);
            }
            changed();
            // A save or revert that came after the record was looked at is read again.
            if (!Objects.equals(recorded(), this.seen)) readCopies(true);
        }));
    }

    /** Lists the packs the content could be saved into, the current one selected; an opened pack's file shows none. */
    private void showTargets(List<Path> targets, Path current) {
        this.listingTargets = true;
        try {
            this.target.removeAllItems();
            targets.forEach(this.target::addItem);
            this.target.setSelectedItem(current);
        } finally {
            this.listingTargets = false;
        }
        this.targetLabel.setVisible(!targets.isEmpty());
        this.target.setVisible(!targets.isEmpty());
    }

    /** Names the pack the shown content belongs to, and follows its entry in the change record. */
    private void showPack(Path pack) {
        this.pack = pack;
        this.packName = "the " + PackFolders.label(pack);
        this.seen = recorded();
        this.save.setToolTipText(saveTooltip());
    }

    /** Whether the shown content differs from the pack's; call it after every change of the view. */
    protected final void changed() {
        boolean edited = !same(shown(), this.packContent);
        this.save.setVisible(edited);
        this.discard.setVisible(edited);
        this.save.setEnabled(!this.busy && !this.following);
        this.discard.setEnabled(!this.busy);
        this.target.setEnabled(!this.busy);
    }

    /** Names where the content comes from, followed by {@code detail} when it is not empty. */
    private void showState(String detail) {
        // A copy Companion did not write, such as a file of the player's own pack, is in the pack but not edited.
        String source = this.managed ? (this.seen != null ? "Edited in " : "In ") + this.packName
                : this.opened != null ? "Not in " + this.packName + " any more" : "From " + this.origin;
        this.state.setText(detail.isEmpty() ? source : source + ", " + detail);
    }

    /** Checks the content, writes it into the pack, then shows what the game uses and what its reload reported. */
    final void save() {
        if (this.busy || this.following || same(shown(), this.packContent)) return;
        V edited = copy(shown());
        Optional<String> problem = check(edited);
        if (problem.isPresent()) {
            showNotice(problem.get(), ThemeColors::error);
            return;
        }
        this.writes++;
        this.busy = true;
        changed();
        this.state.setText("Reloading in the game");
        this.saving = edited;
        Path into = this.opened != null ? this.opened : this.pack;
        Map<String, byte[]> alongside = alongside();
        String expected = this.baseline;
        String[] written = new String[1];
        // Encoding a large texture takes a while, so it runs with the rest of the save, and so does its hash.
        CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = encode(edited);
                written[0] = ResourceOriginals.hash(bytes);
                return bytes;
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }).thenCompose(bytes -> this.edits.save(this.path, into, bytes, alongside, expected))
                .whenComplete((saved, failure) -> SwingUtilities.invokeLater(() -> {
                    this.busy = false;
                    this.saving = null;
                    if (this.disposed) return;
                    if (failure != null) {
                        showState("");
                        changed();
                        // Asked before following a change that came during the save, which would hold the overwrite back.
                        if (cause(failure) instanceof ResourceEdits.ChangedSince changedSince && this.askToReplace.test(changedSince)) {
                            this.baseline = null;
                            save();
                            return;
                        }
                        followLater();
                        showNotice("Not saved: " + message(failure), ThemeColors::error);
                        return;
                    }
                    this.baseline = written[0];
                    this.baselinePack = saved.pack();
                    this.packHash = written[0];
                    boolean keepEdits = !same(shown(), edited);
                    showPack(saved.pack());
                    this.packContent = edited;
                    this.managed = true;
                    if (keepEdits) markSaved(edited);
                    else load(edited);
                    // A copy the game does not use does not apply, however the reload went.
                    showState(saved.reloadFailure().isEmpty() && saved.unused().isEmpty() ? saved.effect().description() : "");
                    showResult(saved.problems(), saved.reloadFailure());
                    if (saved.reloadFailure().isEmpty() && saved.problems().isEmpty() && !saved.unused().isEmpty()) {
                        showNotice(saved.unused(), ThemeColors::warning);
                        this.readNotice = saved.unused();
                    }
                    changed();
                    followLater();
                }));
    }

    /** Follows a working pack or world change that came during the save just completed. */
    private void followLater() {
        if (!this.followAfterSave) return;
        boolean changed = this.clearAfterSave;
        this.followAfterSave = false;
        this.clearAfterSave = false;
        follow(changed);
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

    /** Drops the unsaved changes, back to the pack's content. */
    void discard() {
        load(this.packContent);
        this.baseline = this.packHash;
        showNotice("", ThemeColors::secondaryText);
        changed();
    }

    /** Whether the content can be left: it has no unsaved changes, or they were discarded after asking. */
    boolean confirmLeave() {
        // A save under way is written whether or not the tab stays open.
        V shown = shown();
        if (same(shown, this.packContent) || (this.saving != null && same(shown, this.saving))) return true;
        String name = this.path.substring(this.path.lastIndexOf('/') + 1);
        Object[] options = {"Discard", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, "The " + noun() + " of " + name + " has unsaved changes.",
                "Unsaved changes", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (choice != 0) return false;
        discard();
        return true;
    }

    protected final void showNotice(String message, Supplier<Color> color) {
        this.noticeColor = color;
        this.notice.setForeground(color.get());
        this.notice.setText(message);
        this.notice.setToolTipText(null);
        this.notice.setVisible(!message.isEmpty());
    }

    /** Whether to save over a copy written since this tab read the pack's, after asking. */
    private boolean replaceChangedCopy(ResourceEdits.ChangedSince changedSince) {
        Object[] options = {"Overwrite", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, changedSince.getMessage() + ". Overwrite it with this tab's " + noun() + "?",
                "Changed since", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        return choice == 0;
    }

    private static Throwable cause(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        return cause;
    }

    private static String message(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    void dispose() {
        this.disposed = true;
        this.stopListening.run();
        this.stopFollowingPacks.run();
        this.stopFollowingWorkingPack.run();
    }
}
