package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.pack.JsonFormat;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourcePaths;
import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.LinkLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A text resource of the pack, edited in place (see {@link PackResourceEditor}); Save checks the text as the game reads it.
 * JSON is laid out one value per line with Reformat Code (Ctrl+Alt+L), as an edit Save writes and Undo takes back; a
 * file of one long line, such as a minified language file, offers it above the text.
 */
final class ResourceTextEditor extends PackResourceEditor<String> {
    private static final Pattern LINE = Pattern.compile("line (\\d+)");
    /** A line longer than this, alone in its file, is offered Reformat Code. */
    private static final int LONG_LINE = 1_000;

    private final EditableTextPanel text;
    /** Offers Reformat Code above a file of one long line. */
    private final JPanel oneLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JLabel oneLineSize = new JLabel();
    private final Action reformat = new AbstractAction("Reformat Code", Icons.REFORMAT_CODE) {
        @Override
        public void actionPerformed(ActionEvent event) {
            reformat();
        }
    };
    private final LinkLabel reformatLink = new LinkLabel("Reformat Code", null,
            Tooltip.action("Reformat Code", "Ctrl+Alt+L").text("Lays the JSON out one value per line").html(), this::reformat);
    /** Why the last Reformat Code left the text as it was, shown until the text changes. */
    private String reformatProblem;

    ResourceTextEditor(String path, String origin, Path pack, LoadedResource.Text content, ResourceEdits edits) {
        super(path, origin, pack, content.value(), edits);
        this.text = new EditableTextPanel(content.syntaxStyle(), this::changed, this::save);
        this.text.load(content.value());
        JPanel view = new JPanel(new BorderLayout());
        if (JsonFormat.formats(path)) {
            this.reformat.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke("ctrl alt L"));
            this.text.editorPane.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl alt L"), "reformatCode");
            this.text.editorPane.getActionMap().put("reformatCode", this.reformat);
            this.text.editorPane.getPopupMenu().addSeparator();
            this.text.editorPane.getPopupMenu().add(this.reformat);
            ThemeColors.keepForeground(this.oneLineSize, ThemeColors::secondaryText);
            this.oneLine.add(this.oneLineSize);
            this.oneLine.add(this.reformatLink);
            this.oneLine.setBorder(UiMetrics.noticePadding());
            view.add(this.oneLine, BorderLayout.NORTH);
            this.text.editorPane.getDocument().addDocumentListener((DocumentChangeListener) event -> {
                showOneLine();
                clearReformatProblem();
            });
            showOneLine();
        } else {
            this.oneLine.setVisible(false);
        }
        view.add(this.text, BorderLayout.CENTER);
        start(view);
    }

    /** Whether Reformat Code is offered above the text, for tests. */
    boolean oneLineOffered() {
        return this.oneLine.isVisible();
    }

    /** Shows the offer to lay the text out while it is one long line, which may end in a line break. */
    private void showOneLine() {
        int lines = this.text.editorPane.getLineCount();
        boolean one = this.text.editorPane.getDocument().getLength() > LONG_LINE && (lines == 1 || lines == 2 && endsInLineBreak());
        if (one) {
            int bytes = this.text.text().getBytes(StandardCharsets.UTF_8).length;
            this.oneLineSize.setText("One line of " + String.format(Locale.ROOT, "%.1f", bytes / 1024d) + " KB");
        }
        this.oneLine.setVisible(one);
    }

    private boolean endsInLineBreak() {
        Document document = this.text.editorPane.getDocument();
        try {
            return document.getText(document.getLength() - 1, 1).equals("\n");
        } catch (BadLocationException impossible) {
            return false;
        }
    }

    private void clearReformatProblem() {
        if (this.reformatProblem != null && noticeText().equals(this.reformatProblem)) showNotice("", ThemeColors::secondaryText);
        this.reformatProblem = null;
    }

    /** Lays the JSON out one value per line, as one edit; text that is not strict JSON is left as it is, saying why. */
    void reformat() {
        // Before the working pack's copy is read, the text on screen is not what a save would write over.
        if (!this.text.editorPane.isEditable()) return;
        String shown = this.text.text();
        String formatted;
        try {
            formatted = JsonFormat.format(shown);
        } catch (IllegalArgumentException invalid) {
            this.reformatProblem = "Not reformatted: " + invalid.getMessage();
            showNotice(this.reformatProblem, ThemeColors::error);
            return;
        }
        clearReformatProblem();
        if (formatted.equals(shown)) return;
        this.text.editorPane.beginAtomicEdit();
        try {
            this.text.editorPane.replaceRange(formatted, 0, shown.length());
            this.text.editorPane.setCaretPosition(0);
        } finally {
            this.text.editorPane.endAtomicEdit();
        }
    }

    EditableTextPanel textPanel() {
        return this.text;
    }

    @Override
    protected String shown() {
        return this.text.text();
    }

    @Override
    protected boolean same(String first, String second) {
        return first.equals(second);
    }

    @Override
    protected void load(String content) {
        this.text.load(content);
    }

    @Override
    protected void markSaved(String content) {
        this.text.markSaved(content);
    }

    @Override
    protected boolean modified() {
        return this.text.modified();
    }

    @Override
    protected String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    protected byte[] encode(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected String none() {
        return "";
    }

    @Override
    protected void setEditable(boolean editable) {
        this.text.setEditable(editable);
        this.reformat.setEnabled(editable);
        this.reformatLink.setEnabled(editable);
    }

    @Override
    protected String noun() {
        return "text";
    }

    /** Checks the text as the game parses it, and moves to the line a problem names. */
    @Override
    protected Optional<String> check(String content) {
        Optional<String> problem = ResourcePaths.check(path(), content);
        problem.map(LINE::matcher).filter(Matcher::find).ifPresent(line -> this.text.goToLine(Integer.parseInt(line.group(1))));
        return problem;
    }

    @Override
    void dispose() {
        super.dispose();
        this.text.dispose();
    }
}
