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
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A text resource of the pack, edited in place. It shows what the running game uses: a copy tried in its memory, the
 * managed pack's copy, or the file that was opened. Try in Game puts the text into the game's memory without writing a
 * file; Save (Ctrl+S) checks the text, writes it into the pack Companion manages and ends a try. Both reload what the
 * game needs, and the bar tells where the text comes from, when the game uses it and what its reload reported.
 */
final class ResourceTextEditor extends JPanel {
    private static final Pattern LINE = Pattern.compile("line (\\d+)");

    /**
     * The managed pack the text is saved in, its copy there or null, the copy tried in the game or null, and a pack above
     * the managed one that supplies the file too, or null.
     */
    private record Found(Path pack, String managed, String tried, String overriddenBy) {
    }

    private final String path;
    private final String origin;
    private final ResourceEdits edits;
    private final EditableTextPanel text;
    private final JLabel state = new JLabel();
    private final JLabel notice = new JLabel();
    private final JButton tryInGame = new JButton("Try in Game", Icons.RUN);
    private final JButton save = new JButton("Save", Icons.SAVE);
    private final JButton discard = new JButton("Discard", Icons.REVERT);
    private final JButton revertInGame = new JButton("Revert in Game", Icons.REVERT);
    private Supplier<Color> noticeColor = ThemeColors::secondaryText;
    /** The text the pack supplies: the managed pack's copy, or the opened file's. */
    private String packText;
    /** The text tried in the game, or null. */
    private String triedText;
    /** The managed pack the text is saved in, or null for the current world's until it is known. */
    private Path pack;
    /** The managed pack the text is saved in, as the bar names it. */
    private String packName = "the TotalDebug pack";
    /** Counts writes, so the copies read when the tab opened never replace the text of a later write. */
    private int writes;
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
        this.pack = pack;
        this.edits = Objects.requireNonNull(edits, "edits");
        this.packText = content.value();
        this.text = new EditableTextPanel(content.syntaxStyle(), this::changed, this::save);
        this.text.load(content.value());

        this.tryInGame.setToolTipText(Tooltip.action("Try in Game", null)
                .text("Checks the text and puts it into the running game's memory until the game closes; writes no file").html());
        this.tryInGame.addActionListener(event -> tryInGame());
        this.save.setToolTipText(Tooltip.action("Save", "Ctrl+S").text(path.startsWith("assets/")
                ? "Checks the text, writes it into the TotalDebug resource pack and reloads it in the game"
                : "Checks the text, writes it into the TotalDebug datapack of the current world and reloads it in the game").html());
        this.save.addActionListener(event -> save());
        this.discard.setToolTipText(Tooltip.action("Discard", null).text("Drops the unsaved changes to the text").html());
        this.discard.addActionListener(event -> discard());
        this.revertInGame.setToolTipText(Tooltip.action("Revert in Game", null)
                .text("Removes the tried text from the game's memory, so it uses the pack's copy again").html());
        this.revertInGame.addActionListener(event -> revertInGame());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(this.revertInGame);
        actions.add(this.tryInGame);
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
        readCopies();
    }

    EditableTextPanel textPanel() {
        return this.text;
    }

    /** Shows the copy the game uses instead of the opened file's, and names a pack that overrides the managed one. */
    private void readCopies() {
        int started = this.writes;
        Path opened = this.pack;
        CompletableFuture.supplyAsync(() -> {
            try {
                Path pack = opened != null ? opened : this.edits.pack(this.path);
                return new Found(pack, this.edits.managed(pack, this.path).map(ResourceTextEditor::text).orElse(null),
                        this.edits.tried(this.path).map(ResourceTextEditor::text).orElse(null),
                        this.edits.overriddenBy(this.path).orElse(null));
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((found, failure) -> SwingUtilities.invokeLater(() -> {
            if (this.disposed || this.writes != started) return;
            if (failure != null) {
                showNotice(message(failure), ThemeColors::error);
                return;
            }
            this.pack = found.pack();
            this.packName = packName(found.pack());
            if (found.managed() != null) {
                this.packText = found.managed();
                this.managed = true;
            }
            this.triedText = found.tried();
            if (!this.text.modified()) this.text.load(shown());
            else this.text.markSaved(shown());
            showState("");
            if (found.overriddenBy() != null) {
                showNotice(found.overriddenBy() + " is above the TotalDebug pack and supplies this file too, so the game shows its copy",
                        ThemeColors::warning);
            }
            changed();
        }));
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

    /** The text the game uses: the tried copy, or the pack's. */
    private String shown() {
        return this.triedText != null ? this.triedText : this.packText;
    }

    private void changed() {
        String current = this.text.text();
        boolean differsFromGame = !current.equals(shown());
        this.tryInGame.setVisible(differsFromGame);
        this.save.setVisible(!current.equals(this.packText));
        this.discard.setVisible(differsFromGame);
        this.revertInGame.setVisible(this.triedText != null);
        for (JButton button : List.of(this.tryInGame, this.save, this.discard, this.revertInGame)) button.setEnabled(!this.busy);
    }

    /** Names where the text the game uses comes from, followed by {@code detail} when it is not empty. */
    private void showState(String detail) {
        String source = this.triedText != null ? "Tried in the game"
                : this.managed ? "Edited in " + this.packName : "From " + this.origin;
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

    /** Checks the text and writes it into the managed pack. */
    void save() {
        if (this.busy || this.text.text().equals(this.packText)) return;
        String edited = checked();
        if (edited == null) return;
        run(this.edits.save(this.path, this.pack, edited.getBytes(StandardCharsets.UTF_8)), "Not saved: ", saved -> {
            this.packText = edited;
            this.managed = true;
            this.triedText = null;
        });
    }

    /** Checks the text and puts it into the game's memory. */
    void tryInGame() {
        if (this.busy || this.text.text().equals(shown())) return;
        String edited = checked();
        if (edited == null) return;
        run(this.edits.tryInGame(this.path, edited.getBytes(StandardCharsets.UTF_8)), "Not tried: ",
                saved -> this.triedText = edited);
    }

    /** Removes the tried text from the game's memory. */
    void revertInGame() {
        if (this.busy || this.triedText == null) return;
        ChangeRecord.Change change = this.edits.record().change(new ChangeRecord.Resource(this.path, null), ChangeRecord.Level.GAME);
        if (change == null) {
            this.triedText = null;
            this.text.markSaved(shown());
            showState("");
            changed();
            return;
        }
        run(this.edits.revert(change), "Not reverted: ", saved -> this.triedText = null);
    }

    /** Runs a write, then shows what the game uses and what its reload reported. */
    private void run(CompletableFuture<ResourceEdits.Saved> write, String failurePrefix, Consumer<ResourceEdits.Saved> done) {
        this.writes++;
        this.busy = true;
        changed();
        this.state.setText("Reloading in the game");
        write.whenComplete((saved, failure) -> SwingUtilities.invokeLater(() -> {
            this.busy = false;
            if (this.disposed) return;
            if (failure != null) {
                showState("");
                showNotice(failurePrefix + message(failure), ThemeColors::error);
                changed();
                return;
            }
            boolean keepEdits = !this.text.text().equals(shown());
            done.accept(saved);
            if (keepEdits) this.text.markSaved(shown());
            else this.text.load(shown());
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

    /** Drops the unsaved changes, back to the text the game uses. */
    void discard() {
        this.text.load(shown());
        showNotice("", ThemeColors::secondaryText);
        changed();
    }

    /** Whether the text can be left: it has no changes the game does not use, or they were discarded after asking. */
    boolean confirmLeave() {
        if (this.text.text().equals(shown())) return true;
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
        this.text.dispose();
    }
}
