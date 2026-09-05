package com.github.minecraft_ta.totalDebugCompanion.search;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

public class SearchManager {
    private static final System.Logger LOGGER = System.getLogger(SearchManager.class.getName());

    /**
     * Rebuilt per use rather than cached in a static field: a highlight painter captures its colour
     * on construction, so a cached one would keep painting the old theme's accent after a switch.
     */
    private static DefaultHighlighter.DefaultHighlightPainter highlightPainter() {
        return new DefaultHighlighter.DefaultHighlightPainter(ThemeColors.accent());
    }

    private static DefaultHighlighter.DefaultHighlightPainter focusedHighlightPainter() {
        return new DefaultHighlighter.DefaultHighlightPainter(ThemeColors.accent().darker());
    }

    private final RSyntaxTextArea textPane;
    private final List<IntConsumer> focusedIndexChangeListeners = new ArrayList<>();

    private final List<HighlightInfo> highlights = new ArrayList<>();
    private final Deque<String> searchQueue = new ConcurrentLinkedDeque<>();
    private final Thread searchThread;

    private Object focusedMatchReference;
    private int focusedMatchIndex;

    private boolean matchCase;
    private boolean useRegex;

    public SearchManager(RSyntaxTextArea textPane) {
        this.textPane = textPane;
        this.searchThread = new Thread(() -> {
            while (true) {
                synchronized (this.searchQueue) {
                    if (this.searchQueue.isEmpty()) {
                        try {
                            this.searchQueue.wait();
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }

                var query = this.searchQueue.removeLast();
                this.searchQueue.clear();
                this.highlights.clear();

                var text = textPane.getText();

                Matcher matcher;
                try {
                    int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE;
                    matcher = Pattern.compile(this.useRegex ? query : Pattern.quote(query), flags).matcher(text);
                } catch (Throwable t) {
                    this.focusedMatchReference = null;
                    this.focusedIndexChangeListeners.forEach(i -> i.accept(0));
                    this.focusedMatchIndex = 0;
                    SwingUtilities.invokeLater(this::hideHighlights);
                    continue;
                }

                while (matcher.find()) {
                    if (!this.searchQueue.isEmpty())
                        break;
                    if (matcher.start() == matcher.end())
                        continue;
                    this.highlights.add(new HighlightInfo(matcher.start(), matcher.end()));
                }

                //If we weren't interrupted, display all results
                if (matcher.hitEnd()) {
                    this.focusedMatchReference = null;
                    this.focusedIndexChangeListeners.forEach(i -> i.accept(0));
                    this.focusedMatchIndex = 0;
                    final var highlightsCopy = new ArrayList<>(this.highlights);
                    SwingUtilities.invokeLater(() -> {
                        showHighlights(highlightsCopy);
                    });
                }
            }
        });
        this.searchThread.setDaemon(true);
        this.searchThread.setName("Text search thread");
        this.searchThread.start();
    }

    public void hideHighlights() {
        this.textPane.getHighlighter().removeAllHighlights();
    }

    public void focusNextMatch() {
        if (this.focusedMatchIndex >= getMatchCount() - 1)
            focusMatchAtIndex(0);
        else
            focusMatchAtIndex(this.focusedMatchIndex + 1);
    }

    public void focusPreviousMatch() {
        if (this.focusedMatchIndex <= 0)
            focusMatchAtIndex(getMatchCount() - 1);
        else
            focusMatchAtIndex(this.focusedMatchIndex - 1);
    }

    public int getMatchCount() {
        return this.highlights.size();
    }

    public void stopThread() {
        this.searchThread.interrupt();
    }

    public void setQuery(String query) {
        if (query == null || query.isEmpty()) {
            this.textPane.getHighlighter().removeAllHighlights();
            this.highlights.clear();
            this.focusedIndexChangeListeners.forEach(i -> i.accept(0));
            this.focusedMatchIndex = 0;
            this.focusedMatchReference = null;
            return;
        }

        synchronized (this.searchQueue) {
            this.searchQueue.notify();
            this.searchQueue.add(query);
        }
    }

    public void setUseRegex(boolean useRegex) {
        this.useRegex = useRegex;
    }

    public int getFocusedRangeStart() {
        if (this.highlights.isEmpty())
            return 0;
        return this.highlights.get(this.focusedMatchIndex).start;
    }

    public int getFocusedRangeEnd() {
        if (this.highlights.isEmpty())
            return 0;
        return this.highlights.get(this.focusedMatchIndex).end;
    }

    public void addFocusedIndexChangedListener(IntConsumer listener) {
        this.focusedIndexChangeListeners.add(listener);
    }

    private void focusMatchAtIndex(int index) {
        if (this.highlights.isEmpty())
            return;

        try {
            //Add back the normal highlighting
            var oldMatchPos = this.highlights.get(this.focusedMatchIndex);
            if (oldMatchPos.highlightReference != null)
                textPane.getHighlighter().removeHighlight(oldMatchPos.highlightReference);
            oldMatchPos.highlightReference = textPane.getHighlighter().addHighlight(oldMatchPos.start, oldMatchPos.end, highlightPainter());

            //Remove the focus highlighting
            if (this.focusedMatchReference != null)
                textPane.getHighlighter().removeHighlight(this.focusedMatchReference);

            this.focusedIndexChangeListeners.forEach(i -> i.accept(index));
            this.focusedMatchIndex = index;

            //Remove normal highlighting at new position
            textPane.getHighlighter().removeHighlight(this.highlights.get(this.focusedMatchIndex).highlightReference);

            //Add focus highlighting at new position
            var matchPos = this.highlights.get(this.focusedMatchIndex);
            this.focusedMatchReference = textPane.getHighlighter().addHighlight(matchPos.start, matchPos.end, focusedHighlightPainter());
        } catch (BadLocationException ex) {
            LOGGER.log(System.Logger.Level.WARNING, "Unable to focus search match " + this.focusedMatchIndex, ex);
        }
    }

    private void showHighlights(List<HighlightInfo> highlights) {
        textPane.getHighlighter().removeAllHighlights();
        highlights.forEach(p -> {
            try {
                p.highlightReference = textPane.getHighlighter().addHighlight(p.start, p.end, highlightPainter());
            } catch (BadLocationException e) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Unable to highlight search match at " + p.start + ".." + p.end, e);
            }
        });

        focusMatchAtIndex(0);
    }

    public void setMatchCase(boolean matchCase) {
        this.matchCase = matchCase;
    }

    private static final class HighlightInfo {

        private final int start;
        private final int end;

        private Object highlightReference;

        public HighlightInfo(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
