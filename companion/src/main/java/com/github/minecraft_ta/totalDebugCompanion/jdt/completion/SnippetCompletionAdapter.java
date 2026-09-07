package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SnippetCompletionAdapter {

    private static final String SNIPPET_NEXT_ACTION_KEY = "SnippetCompletionAdapter.snippetNextAction";
    private static final Matcher SNIPPET_MATCHER = Pattern.compile("\\$\\{(\\d+)(:([\\w\\d]+))?}").matcher("");
    private static final Highlighter.HighlightPainter EMPTY_HIGHLIGHT_PAINTER = new DefaultHighlighter.DefaultHighlightPainter(null) {
        @Override
        public void paint(Graphics g, int offs0, int offs1, Shape bounds, JTextComponent c) {
        }

        @Override
        public Shape paintLayer(Graphics g, int offs0, int offs1, Shape bounds, JTextComponent c, View view) {
            return null;
        }
    };
    private static final Highlighter.HighlightPainter SNIPPET_HIGHLIGHT_PAINTER;
    static {
        try {
            var ctor = Class.forName("org.fife.ui.autocomplete.OutlineHighlightPainter").getDeclaredConstructor(Color.class);
            ctor.setAccessible(true);
            SNIPPET_HIGHLIGHT_PAINTER = (Highlighter.HighlightPainter) ctor.newInstance(Color.gray);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private final List<HighlightInfo> highlights = new ArrayList<>();
    private final Listener listener = new Listener();
    private final JTextComponent textComponent;

    private Object oldTabKey;
    private Object oldEnterKey;
    private Action oldEnterAction;

    public SnippetCompletionAdapter(JTextComponent textComponent) {
        this.textComponent = textComponent;
    }

    public void insert(CustomTextEdit... textEdits) {
        deactivate();

        Arrays.sort(textEdits, Comparator.<CustomTextEdit>comparingInt(a -> a.getRange().getOffset()).thenComparingInt(a -> a.getRange().getLength()));

        var totalOffset = 0;
        for (CustomTextEdit textEdit : textEdits) {
            var snippetText = textEdit.getNewText();
            SNIPPET_MATCHER.reset(snippetText);
            var results = SNIPPET_MATCHER.results().collect(Collectors.toCollection(ArrayList::new));
            if (results.isEmpty())
                throw new IllegalArgumentException("Snippet text must contain at least one placeholder");

            Collections.reverse(results);

            try {
                record ReplacementInfo(int n, int start, int end, int offset, String placeholder) {}
                var highlightInfo = new ArrayList<ReplacementInfo>();

                var newText = new StringBuilder(snippetText);
                for (var match : results) {
                    var replacement = match.group(3) == null ? "" : match.group(3);
                    newText.replace(match.start(), match.end(), replacement);

                    highlightInfo.add(0, new ReplacementInfo(Integer.parseInt(match.group(1)), match.start(), match.end(), match.end() - match.start() - replacement.length(), replacement));
                }

                ((AbstractDocument) this.textComponent.getDocument()).replace(textEdit.getRange().getOffset() + totalOffset, textEdit.getRange().getLength(), newText.toString(), null);

                var offset = 0;
                for (var match : highlightInfo) {
                    var pos = textEdit.getRange().getOffset() + totalOffset + match.start() - offset - 1;
                    this.highlights.add(new HighlightInfo(
                            match.n,
                            (Highlighter.Highlight) this.textComponent.getHighlighter().addHighlight(pos, pos + match.placeholder().length() + 1, match.n == 0 ? EMPTY_HIGHLIGHT_PAINTER : SNIPPET_HIGHLIGHT_PAINTER)
                    ));
                    offset += match.offset();
                }

                totalOffset += newText.length();
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        }

        this.highlights.sort((a, b) -> {
            if (a.n == 0)
                return 1;
            return Integer.compare(a.n, b.n);
        });

        activate();
        focusHighlightInfo(this.highlights.get(0));
    }

    public void beginIgnoredDocumentChange() {
        this.listener.ignoreDocumentChanges = true;
    }

    public void endIgnoredDocumentChange() {
        this.listener.ignoreDocumentChanges = false;
    }

    public static boolean isSnippet(String text) {
        SNIPPET_MATCHER.reset(text);
        return SNIPPET_MATCHER.find();
    }

    private void moveToNextParam() {
        var p = getPlaceholderAt(this.textComponent.getCaretPosition());
        if (p == null) {
            deactivate();
            return;
        }

        removeHighlightInfo(p);

        if (this.highlights.isEmpty()) {
            deactivate();
            return;
        }

        focusHighlightInfo(this.highlights.get(0));
    }

    private void activate() {
        var inputMap = this.textComponent.getInputMap();
        var actionMap = this.textComponent.getActionMap();

        var tabKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0);
        oldTabKey = inputMap.get(tabKeyStroke);
        inputMap.put(tabKeyStroke, SNIPPET_NEXT_ACTION_KEY);
        actionMap.put(SNIPPET_NEXT_ACTION_KEY, new NextParamAction());

        var enterKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
        oldEnterKey = inputMap.get(enterKeyStroke);
        inputMap.put(enterKeyStroke, SNIPPET_NEXT_ACTION_KEY);
        oldEnterAction = actionMap.get(oldEnterKey);
        actionMap.put(SNIPPET_NEXT_ACTION_KEY, new NextParamAction());

        this.textComponent.getDocument().addDocumentListener(this.listener);
    }

    private void deactivate() {
        if (this.oldTabKey == null)
            return;

        this.highlights.forEach(this::removeHighlightInfoFromHighlighter);
        this.highlights.clear();

        var inputMap = this.textComponent.getInputMap();
        var actionMap = this.textComponent.getActionMap();

        var tabKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0);
        inputMap.put(tabKeyStroke, oldTabKey);
        actionMap.remove(SNIPPET_NEXT_ACTION_KEY);

        var enterKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
        inputMap.put(enterKeyStroke, oldEnterKey);
        actionMap.put(oldEnterKey, oldEnterAction);

        oldTabKey = null;
        oldEnterKey = null;
        oldEnterAction = null;

        this.textComponent.getDocument().removeDocumentListener(this.listener);
    }

    private void focusHighlightInfo(HighlightInfo highlightInfo) {
        this.textComponent.setSelectionStart(highlightInfo.reference().getStartOffset() + 1);
        this.textComponent.setSelectionEnd(highlightInfo.reference().getEndOffset());

        //We're not needed anymore for the last placeholder
        if (this.highlights.size() < 2)
            deactivate();
    }

    private void removeHighlightInfo(HighlightInfo highlightInfo) {
        this.highlights.removeIf(h -> {
            var remove = h.n == highlightInfo.n();
            if (remove)
                removeHighlightInfoFromHighlighter(h);
            return remove;
        });
    }

    private void removeHighlightInfoFromHighlighter(HighlightInfo highlightInfo) {
        this.textComponent.getHighlighter().removeHighlight(highlightInfo.reference());
    }

    private class NextParamAction extends TextAction {

        public NextParamAction() {
            super(SNIPPET_NEXT_ACTION_KEY);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            moveToNextParam();
        }
    }

    private class Listener implements DocumentListener {

        private boolean ignoreDocumentChanges;
        private boolean updatingClonedHighlights;

        @Override
        public void changedUpdate(DocumentEvent e) {
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            handleDocumentChange(textComponent.getCaretPosition());
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            handleDocumentChange(e.getOffset());
        }

        private void handleDocumentChange(int pos) {
            var p = getPlaceholderAt(pos);

            //Update linked placeholders
            if (!this.updatingClonedHighlights && p != null)
                possiblyUpdateLinkedPlaceholders(p);

            if (!this.ignoreDocumentChanges && (p == null || p.n == 0))
                deactivate();
        }

        private void possiblyUpdateLinkedPlaceholders(HighlightInfo p) {
            String text;
            try {
                text = textComponent.getText(p.reference.getStartOffset() + 1, p.reference.getEndOffset() - p.reference.getStartOffset() - 1);
            } catch (BadLocationException e) {
                throw new RuntimeException(e);
            }

            SwingUtilities.invokeLater(() -> {
                final int[] caretPos = {textComponent.getCaretPosition()};
                highlights.forEach(h -> {
                    if (h == p || h.n != p.n)
                        return;

                    boolean wasUpdatingClonedHighlights = this.updatingClonedHighlights;
                    boolean wasIgnoringDocumentChanges = this.ignoreDocumentChanges;
                    this.updatingClonedHighlights = true;
                    this.ignoreDocumentChanges = true;
                    try {
                        //Fix the cursor position by diffing the change
                        if (h.reference.getEndOffset() < p.reference.getStartOffset())
                            caretPos[0] += text.length() - (h.reference.getEndOffset() - h.reference.getStartOffset() - 1);

                        ((AbstractDocument) textComponent.getDocument()).replace(
                                h.reference.getStartOffset() + 1,
                                h.reference.getEndOffset() - h.reference.getStartOffset() - 1,
                                text, null
                        );
                    } catch (BadLocationException e) {
                        e.printStackTrace();
                    } finally {
                        this.ignoreDocumentChanges = wasIgnoringDocumentChanges;
                        this.updatingClonedHighlights = wasUpdatingClonedHighlights;
                    }
                });
                textComponent.setCaretPosition(caretPos[0]);
            });
        }
    }

    private HighlightInfo getPlaceholderAt(int pos) {
        for (HighlightInfo highlight : this.highlights) {
            if (pos >= highlight.reference().getStartOffset() + 1 && pos <= highlight.reference().getEndOffset())
                return highlight;
        }

        return null;
    }

    record HighlightInfo(int n, Highlighter.Highlight reference) {
    }
}
