package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis.Problem;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Presentation of the already accepted diagnostics. Never analyzes or modifies the document. */
final class InlineDiagnostics implements AutoCloseable {
    record Line(int number, List<Problem> problems) { }
    record Label(Rectangle bounds, String text, String tooltip, ParserNotice.Level level) { }
    private final EditorTextArea editor;
    private final JComponent layer;
    private final PropertyChangeListener settingListener;
    private List<Line> lines = List.of();
    private boolean closed;

    InlineDiagnostics(EditorTextArea editor, JComponent layer) {
        this.editor = editor;
        this.layer = layer;
        editor.setOverlayTooltip(point -> tooltipAt(SwingUtilities.convertPoint(editor, point, layer)));
        settingListener = event -> layer.repaint();
        GlobalConfig.getInstance().addInlineDiagnosticsListener(settingListener);
    }

    void setProblems(List<Problem> problems) {
        if (closed) return;
        var grouped = new TreeMap<Integer, List<Problem>>();
        var document = editor.getDocument();
        for (var problem : problems) {
            if (problem.span().start() < 0 || problem.span().end() > document.getLength()
                    || problem.span().end() <= problem.span().start()) continue;
            int line = document.getDefaultRootElement().getElementIndex(problem.span().start());
            grouped.computeIfAbsent(line, ignored -> new ArrayList<>()).add(problem);
        }
        var order = Comparator.comparingInt((Problem problem) -> switch (problem.level()) {
            case ERROR -> 0;
            case WARNING -> 1;
            case INFO -> 2;
        }).thenComparingInt(problem -> problem.span().start())
                .thenComparingInt(Problem::id).thenComparing(Problem::message);
        lines = grouped.entrySet().stream().map(entry -> new Line(entry.getKey(),
                entry.getValue().stream().sorted(order).distinct().toList())).toList();
        layer.repaint();
    }

    List<Line> lines() { return lines; }

    private Font font() {
        // Match IntelliJ inlay sizing while following this editor's font and zoom.
        return editor.getFont().deriveFont(Math.max(1f, editor.getFont().getSize2D() - 1f));
    }

    List<Label> layout() {
        if (closed || !GlobalConfig.getInstance().inlineDiagnostics()) return List.of();
        var labels = new ArrayList<Label>();
        Rectangle visible = editor.getVisibleRect();
        FontMetrics metrics = editor.getFontMetrics(font());
        for (var line : lines) {
            try {
                if (line.number() >= editor.getLineCount() || editor.getFoldManager().isLineHidden(line.number())) continue;
                int start = editor.getLineStartOffset(line.number());
                int end = Math.min(editor.getDocument().getLength(), editor.getLineEndOffset(line.number()));
                while (end > start && Character.isWhitespace(editor.getText(end - 1, 1).charAt(0))) end--;
                var anchor = editor.modelToView2D(end);
                if (anchor == null || anchor.getMaxY() <= visible.y || anchor.getY() >= visible.getMaxY()) continue;
                int x = (int) Math.ceil(anchor.getX()) + 18;
                int available = visible.x + visible.width - x - 8;
                if (x < visible.x || available <= 0) continue;
                Problem primary = line.problems().getFirst();
                String suffix = line.problems().size() > 1 ? "  +" + (line.problems().size() - 1) : "";
                String message = primary.message().replaceAll("\\s+", " ").strip();
                String text = fit(message, suffix, available, metrics);
                if (text.isEmpty()) continue;
                int y = (int) Math.floor(anchor.getY()) + ((int) Math.ceil(anchor.getHeight()) - metrics.getHeight()) / 2;
                Point point = SwingUtilities.convertPoint(editor, x, y, layer);
                String details = "<html>" + String.join("<br>", line.problems().stream()
                        .map(problem -> escape(problem.message()).replace("\n", "<br>")).toList()) + "</html>";
                labels.add(new Label(new Rectangle(point.x, point.y, metrics.stringWidth(text), metrics.getHeight()), text, details, primary.level()));
            } catch (BadLocationException ignored) {
                // Document geometry may be unavailable during layout or document replacement.
            }
        }
        return List.copyOf(labels);
    }

    private static String fit(String message, String suffix, int available, FontMetrics metrics) {
        if (metrics.stringWidth(message + suffix) <= available) return message + suffix;
        String tail = "\u2026" + suffix;
        if (metrics.stringWidth(tail) > available) return "";
        int low = 0, high = message.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (metrics.stringWidth(message.substring(0, middle) + tail) <= available) low = middle;
            else high = middle - 1;
        }
        if (low > 0 && Character.isHighSurrogate(message.charAt(low - 1))) low--;
        return message.substring(0, low).stripTrailing() + tail;
    }

    String tooltipAt(Point point) {
        return layout().stream().filter(label -> label.bounds().contains(point)).map(Label::tooltip).findFirst().orElse(null);
    }

    void paint(Graphics2D graphics) {
        var visible = editor.getVisibleRect();
        Point origin = SwingUtilities.convertPoint(editor, visible.x, visible.y, layer);
        graphics.clip(new Rectangle(origin.x, origin.y, visible.width, visible.height));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(font());
        int ascent = graphics.getFontMetrics().getAscent();
        for (var label : layout()) {
            Color color = switch (label.level()) {
                case ERROR -> ThemeColors.error();
                case WARNING -> ThemeColors.warning();
                case INFO -> ThemeColors.secondaryText();
            };
            graphics.setColor(color);
            graphics.drawString(label.text(), label.bounds().x, label.bounds().y + ascent);
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override public void close() {
        closed = true;
        lines = List.of();
        GlobalConfig.getInstance().removeInlineDiagnosticsListener(settingListener);
        editor.setOverlayTooltip(null);
        layer.repaint();
    }
}
