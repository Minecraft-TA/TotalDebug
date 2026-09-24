package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis.Problem;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.CustomJavaParser;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis.Span;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.folding.Fold;
import org.fife.ui.rsyntaxtextarea.folding.FoldType;
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice.Level;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.Test;
import javax.swing.JLayer;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Point;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import static org.junit.jupiter.api.Assertions.*;

@UiTest
class InlineDiagnosticsTest {
    @Test void groupsByLineAndSeverityWithoutChangingDocumentOrEditorSize() throws Exception {
        edt(() -> {
            try (var f = new Fixture("int a = nope;\nint b = 2;", 700, 220)) {
                Dimension before = f.editor.getPreferredSize();
                String source = f.editor.getText();
                var problems = List.of(problem(8, 12, "Unused value", Level.WARNING),
                        problem(8, 12, "Missing <Type> & value", Level.ERROR), problem(8, 12, "Missing <Type> & value", Level.ERROR));
                f.editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
                var parser = new CustomJavaParser();
                f.editor.addParser(parser);
                parser.setProblems(problems);
                f.editor.forceReparsing(parser);
                f.hints.setProblems(problems);
                var label = f.hints.layout().getFirst();
                assertEquals("Missing <Type> & value  +1", label.text());
                assertEquals(Level.ERROR, label.level());
                assertEquals(1, f.hints.layout().size());
                assertTrue(label.tooltip().contains("&lt;Type&gt; &amp; value"));
                assertTrue(label.tooltip().contains("Unused value"));
                Point p = new Point(label.bounds().x + 1, label.bounds().y + 1);
                assertEquals(label.tooltip(), f.hints.tooltipAt(p));
                Point editorPoint = SwingUtilities.convertPoint(f.layer, p, f.editor);
                f.editor.setUseFocusableTips(false);
                assertEquals(label.tooltip(), f.editor.getToolTipText(new MouseEvent(f.editor, MouseEvent.MOUSE_MOVED, 0, 0, editorPoint.x, editorPoint.y, 0, false)));
                f.paint();
                assertEquals(source, f.editor.getText());
                assertEquals(before, f.editor.getPreferredSize());
                assertEquals(f.editor.getFont().deriveFont(f.editor.getFont().getSize2D() - 1), f.fontFromPaint());
            }
            return null;
        });
    }

    @Test void clipsAndEllipsizesWithinViewportInBothThemesAndKeepsFullTooltip() throws Exception {
        edt(() -> {
            for (var theme : CompanionTheme.available()) {
                ThemeManager.installTheme(theme);
                try (var f = new Fixture("bad();", 300, 180)) {
                    String message = "Cannot resolve method with a deliberately long diagnostic description";
                    f.hints.setProblems(List.of(problem(0, 3, message, Level.ERROR)));
                    var label = f.hints.layout().getFirst();
                    assertTrue(label.text().endsWith("\u2026"));
                    assertTrue(label.tooltip().contains(message));
                    var viewport = SwingUtilities.convertRectangle(f.editor, f.editor.getVisibleRect(), f.layer);
                    assertTrue(viewport.contains(label.bounds()));
                    f.paint();
                    f.resize(55, 180);
                    assertTrue(f.hints.layout().isEmpty(), "No message can overlap code to fit a narrow viewport");
                }
            }
            return null;
        });
    }

    @Test void geometryFollowsWrappingScrollingAndFontChangesAndSkipsFoldedLines() throws Exception {
        edt(() -> {
            String source = "call(veryLongArgumentName, anotherLongArgumentName, finalArgumentName);\nif (true) {\n    missing();\n}\n" + "next();\n".repeat(30);
            try (var f = new Fixture(source, 320, 120)) {
                f.editor.setLineWrap(true);
                f.editor.setWrapStyleWord(true);
                f.resize(320, 120);
                f.hints.setProblems(List.of(problem(0, 4, "Bad call", Level.ERROR)));
                var first = f.hints.layout().getFirst();
                var lineStart = f.editor.modelToView2D(0);
                var lineEnd = f.editor.modelToView2D(source.indexOf('\n'));
                var firstPoint = SwingUtilities.convertPoint(f.layer, first.bounds().getLocation(), f.editor);
                assertTrue(firstPoint.y > lineStart.getY(), "Message belongs beside the last wrapped fragment");
                assertTrue(Math.abs(firstPoint.y - lineEnd.getY()) < f.editor.getLineHeight());
                f.editor.setFont(f.editor.getFont().deriveFont(20f));
                f.resize(420, 180);
                f.paint();
                f.scroll.getViewport().setViewPosition(new Point(0, 350));
                assertTrue(f.hints.layout().isEmpty());
                f.scroll.getViewport().setViewPosition(new Point());
                f.editor.setLineWrap(false);
                f.editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
                f.editor.setCodeFoldingEnabled(true);
                Fold fold = new Fold(FoldType.CODE, f.editor, source.indexOf('{'));
                fold.setEndOffset(source.indexOf('}'));
                f.editor.getFoldManager().setFolds(List.of(fold));
                fold.setCollapsed(true);
                int missing = source.indexOf("missing");
                f.hints.setProblems(List.of(problem(missing, missing + 7, "Hidden error", Level.ERROR)));
                assertTrue(f.hints.layout().isEmpty());
                fold.setCollapsed(false);
                f.resize(700, 300);
                assertEquals(1, f.hints.layout().size());
            }
            return null;
        });
    }

    @Test void inlineTooltipTakesPriorityOverAMultilineParserNoticeOnlyInsideItsLabel() throws Exception {
        edt(() -> {
            try (var f = new Fixture("bad();\nnext();", 700, 200)) {
                f.editor.setUseFocusableTips(false);
                f.editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
                var parser = new CustomJavaParser();
                f.editor.addParser(parser);
                var problems = List.of(problem(0, f.editor.getText().length(), "Multiline error", Level.ERROR),
                        problem(0, 3, "Another problem", Level.WARNING));
                parser.setProblems(problems);
                f.editor.forceReparsing(parser);
                f.hints.setProblems(problems);
                var label = f.hints.layout().getFirst();
                var point = SwingUtilities.convertPoint(f.layer, label.bounds().x + 2, label.bounds().y + 2, f.editor);
                String tooltip = f.editor.getToolTipText(new MouseEvent(f.editor, MouseEvent.MOUSE_MOVED, 0, 0, point.x, point.y, 0, false));
                assertTrue(tooltip.contains("Multiline error") && tooltip.contains("Another problem"));
                var code = f.editor.modelToView2D(1);
                String codeTooltip = f.editor.getToolTipText(new MouseEvent(f.editor, MouseEvent.MOUSE_MOVED, 1, 0, (int) code.getX(), (int) code.getY() + 2, 0, false));
                assertEquals("Multiline error", codeTooltip);
            }
            return null;
        });
    }

    @Test void largeUiFontDoesNotPaintAcrossSmallEditorLines() throws Exception {
        edt(() -> {
            Font previous = UIManager.getFont("Label.font");
            try (var f = new Fixture("bad();\nnext();", 600, 180)) {
                UIManager.put("Label.font", new Font("Dialog", Font.PLAIN, 40));
                f.editor.setFont(new Font("Monospaced", Font.PLAIN, 8));
                f.resize(600, 180);
                f.hints.setProblems(List.of(problem(0, 3, "Bad call", Level.ERROR)));
                var label = f.hints.layout().getFirst();
                var source = f.editor.modelToView2D(0);
                var point = SwingUtilities.convertPoint(f.layer, label.bounds().getLocation(), f.editor);
                assertTrue(point.y >= source.getY());
                assertTrue(point.y + label.bounds().height <= source.getMaxY());
                f.paint();
            } finally { UIManager.put("Label.font", previous); }
            return null;
        });
    }

    @Test void toggleAndDisposalKeepDiagnosticStateAndPreserveTheNormalTooltipSupplier() throws Exception {
        edt(() -> {
            try (var f = new Fixture("bad();", 500, 200)) {
                f.hints.setProblems(List.of(problem(0, 3, "Bad call", Level.ERROR)));
                GlobalConfig.getInstance().setInlineDiagnostics(false);
                assertTrue(f.hints.layout().isEmpty());
                assertEquals(1, f.hints.lines().size());
                GlobalConfig.getInstance().setInlineDiagnostics(true);
                assertEquals(1, f.hints.layout().size());
                f.hints.close();
                assertTrue(f.hints.layout().isEmpty());
                assertNull(f.editor.getToolTipSupplier());
            }
            return null;
        });
    }

    private static Problem problem(int start, int end, String message, Level level) {
        return new Problem(1, message, new Span(start, end), level, null);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static final class Fixture implements AutoCloseable {
        final EditorTextArea editor = new EditorTextArea() {
            @Override public Graphics getGraphics() {
                return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
            }
        };
        final RTextScrollPane scroll = new RTextScrollPane(editor);
        final EditorChromeLayerUI chrome = new EditorChromeLayerUI(editor, scroll.getGutter());
        final JLayer<RTextScrollPane> layer = new JLayer<>(scroll, chrome);
        final InlineDiagnostics hints = new InlineDiagnostics(editor, layer);
        Fixture(String source, int width, int height) {
            GlobalConfig.getInstance().setInlineDiagnostics(true);
            editor.setText(source);
            editor.setFont(new Font("Monospaced", Font.PLAIN, 14));
            chrome.setInlineDiagnostics(hints);
            resize(width, height);
        }
        void resize(int width, int height) {
            layer.setSize(width, height); layer.doLayout(); scroll.doLayout(); scroll.getViewport().doLayout();
            editor.setSize(Math.max(scroll.getViewport().getWidth(), editor.getPreferredSize().width), Math.max(scroll.getViewport().getHeight(), editor.getPreferredSize().height));
            if (editor.getLineWrap()) editor.setSize(scroll.getViewport().getWidth(), editor.getHeight());
            editor.doLayout();
        }
        void paint() {
            var image = new BufferedImage(layer.getWidth(), layer.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try { layer.paint(graphics); } finally { graphics.dispose(); }
        }
        Font fontFromPaint() {
            var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try { hints.paint(graphics); return graphics.getFont(); } finally { graphics.dispose(); }
        }
        @Override public void close() { hints.close(); chrome.setInlineDiagnostics(null); }
    }
}
