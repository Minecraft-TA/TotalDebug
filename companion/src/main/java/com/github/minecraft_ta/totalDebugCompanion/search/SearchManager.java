package com.github.minecraft_ta.totalDebugCompanion.search;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Finds a text or pattern in an editor and marks every match, one of them focused. Matches are searched off the Swing
 * thread in a copy of the text, a search a newer one replaces stopping where it is, and painted by one highlight that
 * draws only the matches in the painted area: a highlight for each match would place every one of them on each paint,
 * which on a file of one long line, such as a minified language file, takes seconds. The marks move with edits and are
 * searched again once typing pauses. Swing thread only, apart from the search itself.
 */
public class SearchManager {
    private static final System.Logger LOGGER = System.getLogger(SearchManager.class.getName());
    /** How long typing in the text waits before the matches are searched again. */
    private static final int EDIT_DELAY_MILLIS = 200;

    private final RSyntaxTextArea textPane;
    /** Searches this editor's text, so a slow pattern here never holds up another editor's search. */
    private final ExecutorService searcher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Text search");
        thread.setDaemon(true);
        return thread;
    });
    /** Told of the focused match when a query or a step to the next or previous match moves it, to show it. */
    private final List<IntConsumer> focusedIndexChangeListeners = new ArrayList<>();
    /** Told whenever the matches or the focused one change, also after an edit, to count them. */
    private final List<Runnable> matchesChangedListeners = new ArrayList<>();
    private final Timer researchAfterEdit;
    private final DocumentListener edits = new DocumentListener() {
        @Override
        public void insertUpdate(DocumentEvent event) {
            moved(event.getOffset(), event.getLength());
        }

        @Override
        public void removeUpdate(DocumentEvent event) {
            moved(event.getOffset(), -event.getLength());
        }

        @Override
        public void changedUpdate(DocumentEvent event) {
        }
    };
    /** A new look replaces the editor's highlighter, which then paints the marks again. */
    private final PropertyChangeListener highlighterReplaced = event -> {
        this.matchesTag = null;
        if (this.starts.length > 0) mark();
    };

    /** Where each match starts and ends, in order. */
    private int[] starts = new int[0];
    private int[] ends = new int[0];
    private int focusedMatchIndex;
    /** The one highlight that paints the matches, or null while none are shown. */
    private Object matchesTag;
    private String query = "";
    private boolean matchCase;
    private boolean useRegex;
    /** Counts searches; only the latest may show its matches, and an older one stops early. */
    private volatile int searches;
    private boolean stopped;

    public SearchManager(RSyntaxTextArea textPane) {
        this.textPane = textPane;
        this.researchAfterEdit = new Timer(EDIT_DELAY_MILLIS, event -> search(false));
        this.researchAfterEdit.setRepeats(false);
        textPane.getDocument().addDocumentListener(this.edits);
        textPane.addPropertyChangeListener("highlighter", this.highlighterReplaced);
    }

    /** Searches the text for {@code query} and marks every match; an empty query clears them. */
    public void setQuery(String query) {
        this.query = query == null ? "" : query;
        search(true);
    }

    public void setMatchCase(boolean matchCase) {
        this.matchCase = matchCase;
    }

    public void setUseRegex(boolean useRegex) {
        this.useRegex = useRegex;
    }

    /** Removes the marks, as when the search bar closes; the next query marks again. */
    public void hideHighlights() {
        this.query = "";
        this.searches++;
        this.researchAfterEdit.stop();
        clear();
    }

    public void focusNextMatch() {
        if (getMatchCount() == 0) return;
        focus(this.focusedMatchIndex >= getMatchCount() - 1 ? 0 : this.focusedMatchIndex + 1, true);
    }

    public void focusPreviousMatch() {
        if (getMatchCount() == 0) return;
        focus(this.focusedMatchIndex <= 0 ? getMatchCount() - 1 : this.focusedMatchIndex - 1, true);
    }

    public int getMatchCount() {
        return this.starts.length;
    }

    /** The match focused now, counted from 0. */
    public int getFocusedIndex() {
        return this.focusedMatchIndex;
    }

    public int getFocusedRangeStart() {
        return getMatchCount() == 0 ? 0 : this.starts[this.focusedMatchIndex];
    }

    public int getFocusedRangeEnd() {
        return getMatchCount() == 0 ? 0 : this.ends[this.focusedMatchIndex];
    }

    public void addFocusedIndexChangedListener(IntConsumer listener) {
        this.focusedIndexChangeListeners.add(listener);
    }

    /** Adds {@code listener}; returns what removes it, as a search bar that closes does. */
    public Runnable addMatchesChangedListener(Runnable listener) {
        this.matchesChangedListeners.add(listener);
        return () -> this.matchesChangedListeners.remove(listener);
    }

    /** Stops searching and following the text, when its editor closes. */
    public void stop() {
        this.stopped = true;
        this.searches++;
        this.researchAfterEdit.stop();
        this.searcher.shutdownNow();
        this.textPane.getDocument().removeDocumentListener(this.edits);
        this.textPane.removePropertyChangeListener("highlighter", this.highlighterReplaced);
    }

    /**
     * Moves the marks with an edit of {@code change} characters at {@code offset}, inserted when positive, removed when
     * negative, as the text's own positions move; the matches are searched again once typing pauses.
     */
    private void moved(int offset, int change) {
        // A search still reading the text as it was before the edit would put its matches in the wrong places.
        this.searches++;
        for (int index = 0; index < this.starts.length; index++) {
            this.starts[index] = moved(this.starts[index], offset, change);
            this.ends[index] = moved(this.ends[index], offset, change);
        }
        if (!this.query.isEmpty() && !this.stopped) this.researchAfterEdit.restart();
    }

    private static int moved(int position, int offset, int change) {
        if (change > 0) return position >= offset ? position + change : position;
        return position <= offset ? position : Math.max(offset, position + change);
    }

    /**
     * Searches the text as it is now in the background, and shows the matches once found unless a newer search started.
     * {@code query} tells a new query, which focuses the first match at or after the caret and shows it; after an edit
     * the focused match keeps its place as far as it can, and the view stays where the user is typing.
     */
    private void search(boolean query) {
        int search = ++this.searches;
        this.researchAfterEdit.stop();
        if (this.query.isEmpty() || this.stopped) {
            clear();
            return;
        }
        String source = this.useRegex ? this.query : Pattern.quote(this.query);
        int flags = this.matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        String text = this.textPane.getText();
        int caret = this.textPane.getCaretPosition();
        this.searcher.execute(() -> {
            // Compiled here too: a long pasted pattern takes a while, and a stale one is never compiled.
            Pattern pattern;
            try {
                pattern = Pattern.compile(source, flags);
            } catch (PatternSyntaxException invalid) {
                SwingUtilities.invokeLater(() -> {
                    if (search == this.searches) clear();
                });
                return;
            }
            int[] foundStarts = new int[64];
            int[] foundEnds = new int[64];
            int count = 0;
            try {
                // The text stops the pattern, even a slow one, as soon as a newer search starts.
                Matcher matcher = pattern.matcher(new Stoppable(text, () -> search != this.searches));
                while (matcher.find()) {
                    if (matcher.start() == matcher.end()) {
                        // An empty match reads no text, so the search looks for a newer one here too.
                        if (search != this.searches) return;
                        continue;
                    }
                    if (count == foundStarts.length) {
                        foundStarts = Arrays.copyOf(foundStarts, count * 2);
                        foundEnds = Arrays.copyOf(foundEnds, count * 2);
                    }
                    foundStarts[count] = matcher.start();
                    foundEnds[count] = matcher.end();
                    count++;
                }
            } catch (CancellationException replaced) {
                return;
            }
            int[] matchStarts = Arrays.copyOf(foundStarts, count);
            int[] matchEnds = Arrays.copyOf(foundEnds, count);
            SwingUtilities.invokeLater(() -> {
                if (search == this.searches) show(matchStarts, matchEnds, query ? caret : getFocusedRangeStart(), query);
            });
        });
    }

    private void clear() {
        show(new int[0], new int[0], 0, true);
    }

    /**
     * Shows the matches, focusing the first that ends after {@code from}; {@code reveal} tells the listeners that show
     * the focused match.
     */
    private void show(int[] matchStarts, int[] matchEnds, int from, boolean reveal) {
        this.starts = matchStarts;
        this.ends = matchEnds;
        if (matchStarts.length == 0) {
            if (this.matchesTag != null) this.textPane.getHighlighter().removeHighlight(this.matchesTag);
            this.matchesTag = null;
            this.focusedMatchIndex = 0;
            if (reveal) this.focusedIndexChangeListeners.forEach(listener -> listener.accept(0));
            this.matchesChangedListeners.forEach(Runnable::run);
            return;
        }
        mark();
        int index = firstEndingAfter(from);
        focus(index < matchStarts.length ? index : 0, reveal);
    }

    /** Covers the text with the one highlight that paints the matches. */
    private void mark() {
        Highlighter highlighter = this.textPane.getHighlighter();
        try {
            int length = this.textPane.getDocument().getLength();
            if (this.matchesTag == null) this.matchesTag = highlighter.addHighlight(0, length, new MatchesPainter());
            else highlighter.changeHighlight(this.matchesTag, 0, length);
        } catch (BadLocationException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Unable to mark the search matches", exception);
        }
    }

    private void focus(int index, boolean reveal) {
        this.focusedMatchIndex = index;
        this.textPane.repaint();
        if (reveal) this.focusedIndexChangeListeners.forEach(listener -> listener.accept(index));
        this.matchesChangedListeners.forEach(Runnable::run);
    }

    /** The first match that ends after {@code offset}, or the number of matches when none does. */
    private int firstEndingAfter(int offset) {
        int low = 0;
        int high = this.ends.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (this.ends[middle] <= offset) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    /** Text that stops a search reading it once {@code stale} says a newer one started. */
    private static final class Stoppable implements CharSequence {
        private final CharSequence text;
        private final BooleanSupplier stale;
        /** Characters read; a pattern may read only every fourth character, so reads are counted, not positions. */
        private int reads;

        private Stoppable(CharSequence text, BooleanSupplier stale) {
            this.text = text;
            this.stale = stale;
        }

        @Override
        public int length() {
            return this.text.length();
        }

        @Override
        public char charAt(int index) {
            // Looking every time would cost more than the search; every 4096th read is soon enough.
            if ((++this.reads & 4095) == 0 && this.stale.getAsBoolean()) throw new CancellationException();
            return this.text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Stoppable(this.text.subSequence(start, end), this.stale);
        }

        @Override
        public String toString() {
            return this.text.toString();
        }
    }

    /** Paints the matches in the painted area only, the focused one darker. */
    private final class MatchesPainter implements Highlighter.HighlightPainter {
        @Override
        public void paint(Graphics graphics, int p0, int p1, Shape bounds, JTextComponent component) {
            if (starts.length == 0) return;
            Rectangle clip = graphics.getClipBounds();
            if (clip == null) clip = bounds.getBounds();
            Color match = ThemeColors.accent();
            Color focused = match.darker();
            // Only the text in the painted area: on a long line, the part of it that is visible. Below the last line a
            // point stands for the end of the text, so the bottom is taken inside the last line the area shows.
            int length = component.getDocument().getLength();
            int bottom = clip.y + clip.height - 1;
            try {
                Rectangle2D end = component.modelToView2D(length);
                if (end != null) bottom = (int) Math.min(bottom, end.getMaxY() - 1);
            } catch (BadLocationException unreachable) {
                // The end of the text always has a place.
            }
            // Row by row, so on several long lines the matches left or right of the area are skipped too.
            int rowHeight = Math.max(1, textPane.getLineHeight());
            for (int y = clip.y; y <= bottom; y += rowHeight) {
                int first = component.viewToModel2D(new Point(clip.x, y));
                int last = component.viewToModel2D(new Point(clip.x + clip.width, y));
                paintMatches(graphics, component, clip, first, last, length, match, focused);
            }
        }

        /** Paints the matches between two offsets of one row. */
        private void paintMatches(Graphics graphics, JTextComponent component, Rectangle clip, int first, int last, int length,
                                  Color match, Color focused) {
            for (int index = firstEndingAfter(first); index < starts.length && starts[index] <= last; index++) {
                if (ends[index] > length) break;
                graphics.setColor(index == focusedMatchIndex ? focused : match);
                try {
                    Rectangle2D start = component.modelToView2D(starts[index]);
                    Rectangle2D end = component.modelToView2D(ends[index]);
                    if (start == null || end == null) continue;
                    int top = (int) start.getY();
                    int height = (int) start.getHeight();
                    if (start.getY() == end.getY()) {
                        graphics.fillRect((int) start.getX(), top, (int) Math.max(1, end.getX() - start.getX()), height);
                        continue;
                    }
                    // A match across lines: from its start to the edge, the lines between whole, and to its end.
                    int right = clip.x + clip.width;
                    graphics.fillRect((int) start.getX(), top, right - (int) start.getX(), height);
                    graphics.fillRect(clip.x, top + height, clip.width, (int) end.getY() - top - height);
                    graphics.fillRect(clip.x, (int) end.getY(), (int) end.getX() - clip.x, (int) end.getHeight());
                } catch (BadLocationException stale) {
                    // Matches of text changed since are searched again shortly.
                }
            }
        }
    }
}
