package com.github.minecraft_ta.totalDebugCompanion.search;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@UiTest
class SearchManagerTest {
    @Test
    void matchesAreFoundFromTheCaretAndStepped() throws Exception {
        RSyntaxTextArea[] area = new RSyntaxTextArea[1];
        SearchManager[] search = new SearchManager[1];
        List<Integer> revealed = new CopyOnWriteArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            area[0] = new RSyntaxTextArea("Mek one, mek two, MEK three");
            area[0].setCaretPosition(5);
            search[0] = new SearchManager(area[0]);
            search[0].addFocusedIndexChangedListener(revealed::add);
            search[0].setQuery("mek");
        });
        awaitOnSwing(() -> search[0].getMatchCount() == 3);
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(9, search[0].getFocusedRangeStart(), "the first match after the caret is focused");
            search[0].focusNextMatch();
            assertEquals(18, search[0].getFocusedRangeStart());
            search[0].focusNextMatch();
            assertEquals(0, search[0].getFocusedRangeStart(), "stepping past the last match goes back to the first");
            search[0].focusPreviousMatch();
            assertEquals(18, search[0].getFocusedRangeStart());
            search[0].setMatchCase(true);
            search[0].setQuery("mek");
        });
        awaitOnSwing(() -> search[0].getMatchCount() == 1);
        SwingUtilities.invokeAndWait(() -> {
            search[0].setUseRegex(true);
            search[0].setQuery("(");
        });
        awaitOnSwing(() -> search[0].getMatchCount() == 0);
        assertTrue(revealed.size() >= 4, "a query and every step show the focused match");
    }

    @Test
    void anEditSearchesAgainWithoutMovingTheView() throws Exception {
        RSyntaxTextArea[] area = new RSyntaxTextArea[1];
        SearchManager[] search = new SearchManager[1];
        List<Integer> revealed = new CopyOnWriteArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            area[0] = new RSyntaxTextArea("gear gear");
            search[0] = new SearchManager(area[0]);
            search[0].setQuery("gear");
        });
        awaitOnSwing(() -> search[0].getMatchCount() == 2);
        SwingUtilities.invokeAndWait(() -> {
            search[0].addFocusedIndexChangedListener(revealed::add);
            area[0].append(" gear");
        });
        awaitOnSwing(() -> search[0].getMatchCount() == 3);
        assertEquals(List.of(), revealed, "typing with the search bar open stays where the user types");
        SwingUtilities.invokeAndWait(() -> {
            area[0].insert("xx", 0);
            assertEquals(2, search[0].getFocusedRangeStart(), "the marks move with the text until it is searched again");
            // A new look replaces the highlighter, which then paints the marks.
            area[0].updateUI();
            assertEquals(1, area[0].getHighlighter().getHighlights().length);
            search[0].hideHighlights();
            assertEquals(0, search[0].getMatchCount());
            assertEquals(0, area[0].getHighlighter().getHighlights().length);
            search[0].stop();
        });
    }

    @Test
    void aFileOfOneLongLinePaintsItsMatchesQuickly() throws Exception {
        // As Mekanism's language file: 250 KB on one line, the letter e in it tens of thousands of times.
        paintsQuickly(minified(250_000));
    }

    @Test
    void aFileOfSeveralLongLinesPaintsItsMatchesQuickly() throws Exception {
        // Only the start of each line is in view; the matches far to its right are not placed.
        String line = minified(60_000);
        paintsQuickly(String.join("\n", line, line, line, line, line));
    }

    private static String minified(int length) {
        StringBuilder text = new StringBuilder("{");
        for (int entry = 0; text.length() < length; entry++) {
            text.append("\"item.mekanism.entry_").append(entry).append("\":\"Mekanism entry number ").append(entry).append("\",");
        }
        return text.append("\"end\":\"end\"}").toString();
    }

    private static void paintsQuickly(String text) throws Exception {
        RTextScrollPane[] scroll = new RTextScrollPane[1];
        SearchManager[] search = new SearchManager[1];
        JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            RSyntaxTextArea area = new RSyntaxTextArea(text);
            area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
            scroll[0] = new RTextScrollPane(area);
            frame[0] = new JFrame();
            frame[0].setContentPane(scroll[0]);
            frame[0].setSize(1200, 800);
            UiTestScope.show(frame[0]);
            search[0] = new SearchManager(area);
            search[0].setQuery("e");
        });
        try {
            awaitOnSwing(() -> search[0].getMatchCount() > 20_000);
            long[] millis = new long[1];
            int[] painted = new int[1];
            SwingUtilities.invokeAndWait(() -> {
                BufferedImage image = new BufferedImage(scroll[0].getWidth(), scroll[0].getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                long start = System.nanoTime();
                scroll[0].paint(graphics);
                millis[0] = (System.nanoTime() - start) / 1_000_000;
                graphics.dispose();
                int accent = ThemeColors.accent().getRGB();
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) if (image.getRGB(x, y) == accent) painted[0]++;
                }
            });
            // A highlight for every match took about 14 seconds for this paint.
            assertTrue(millis[0] < 1_000, "painting took " + millis[0] + " ms");
            assertTrue(painted[0] > 0, "the matches in view are marked");
        } finally {
            SwingUtilities.invokeAndWait(frame[0]::dispose);
        }
    }

    private static void awaitOnSwing(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean[] met = new boolean[1];
        while (true) {
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) return;
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting on the Swing thread");
            Thread.sleep(20);
        }
    }
}
