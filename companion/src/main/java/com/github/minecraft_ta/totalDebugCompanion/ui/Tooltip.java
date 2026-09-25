package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.formdev.flatlaf.util.UIScale;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tooltips in one shape: a main line, a muted line for keys, ids or paths, wrapped paragraphs, label and value lines
 * whose values may carry a code color, and a code block for errors and output. Only tooltips with long text get the
 * reading width; short ones stay as narrow as their content.
 */
public final class Tooltip {
    /** Text longer than this wraps at {@link #WIDTH}. */
    private static final int WRAP_AFTER = 70;
    private static final int WIDTH = 380;
    private static final int CODE_LINES = 40;
    private static final int CODE_COLUMNS = 140;

    private sealed interface Part permits Line, Muted, Paragraph, Fact, Code {
    }

    private record Line(String text) implements Part {
    }

    private record Muted(String text) implements Part {
    }

    private record Paragraph(String text) implements Part {
    }

    private record Fact(String label, String value, Color color) implements Part {
    }

    private record Code(String text) implements Part {
    }

    private final List<Part> parts = new ArrayList<>();

    private Tooltip() {
    }

    /** A tooltip starting with {@code title}, such as a name or a short action. */
    public static Tooltip of(String title) {
        Tooltip tooltip = new Tooltip();
        if (title != null && !title.isBlank()) tooltip.parts.add(new Line(title.strip()));
        return tooltip;
    }

    /** Another main line, for tooltips listing several subjects. */
    public Tooltip line(String text) {
        if (text != null && !text.isBlank()) this.parts.add(new Line(text.strip()));
        return this;
    }

    /** A muted line identifying the subject: a key, an id or a short path. */
    public Tooltip detail(String text) {
        if (text != null && !text.isBlank()) this.parts.add(new Muted(text.strip()));
        return this;
    }

    /** A paragraph of prose; line breaks in it are kept. */
    public Tooltip text(String text) {
        if (text != null && !text.isBlank()) this.parts.add(new Paragraph(text.strip()));
        return this;
    }

    public Tooltip fact(String label, String value) {
        return fact(label, value, null);
    }

    /** A "label value" line; {@code color} draws the value like code of that kind. */
    public Tooltip fact(String label, String value, Color color) {
        if (value != null && !value.isBlank()) this.parts.add(new Fact(label, value, color));
        return this;
    }

    /** Monospaced text such as an error or output, cut to a size a tooltip can show. */
    public Tooltip code(String text) {
        if (text != null && !text.isBlank()) this.parts.add(new Code(bounded(text.strip())));
        return this;
    }

    /** The tooltip text, or null when it has nothing to show. */
    public String html() {
        if (this.parts.isEmpty()) return null;
        // A single short line needs no markup.
        if (this.parts.size() == 1) {
            String plain = switch (this.parts.getFirst()) {
                case Line line -> line.text();
                case Paragraph paragraph -> paragraph.text().contains("\n") ? null : paragraph.text();
                default -> null;
            };
            if (plain != null && plain.length() <= WRAP_AFTER) return plain;
        }
        boolean wide = this.parts.stream().anyMatch(part -> switch (part) {
            case Paragraph paragraph -> paragraph.text().length() > WRAP_AFTER;
            case Muted muted -> muted.text().length() > WRAP_AFTER;
            case Line line -> line.text().length() > WRAP_AFTER;
            default -> false;
        });
        StringBuilder html = new StringBuilder("<html>");
        html.append(wide ? "<div style='width:" + UIScale.scale(WIDTH) + "px'>" : "<div>");
        boolean first = true;
        boolean inFacts = false;
        for (Part part : this.parts) {
            if (part instanceof Fact fact) {
                html.append(inFacts ? "<br>" : first ? "<div>" : "<div style='margin-top:4px'>");
                html.append(escape(fact.label())).append(' ');
                if (fact.color() != null) html.append("<font color='").append(hex(fact.color())).append("'>");
                html.append(escape(fact.value()));
                if (fact.color() != null) html.append("</font>");
                inFacts = true;
                first = false;
                continue;
            }
            if (inFacts) html.append("</div>");
            inFacts = false;
            String spacing = first ? "" : part instanceof Muted ? "" : " style='margin-top:4px'";
            switch (part) {
                case Line line -> html.append("<div").append(spacing).append('>').append(escape(line.text())).append("</div>");
                case Muted muted -> html.append("<div><font color='").append(hex(ThemeColors.secondaryText())).append("'>")
                        .append(escape(muted.text())).append("</font></div>");
                case Paragraph paragraph -> html.append("<div").append(spacing).append('>')
                        .append(escape(paragraph.text()).replace("\n", "<br>")).append("</div>");
                case Code code -> html.append("<pre").append(spacing).append('>').append(escape(code.text())).append("</pre>");
                case Fact ignored -> {
                }
            }
            first = false;
        }
        if (inFacts) html.append("</div>");
        return html.append("</div></html>").toString();
    }

    /**
     * A path short enough to read: its last three parts, marked as shortened when there are more. The full path stays
     * available where the path can be copied.
     */
    public static String shortPath(Path path) {
        if (path == null) return "";
        Path normalized = path.normalize();
        int count = normalized.getNameCount();
        if (count <= 3) return normalized.toString();
        return "…" + normalized.getFileSystem().getSeparator() + normalized.subpath(count - 3, count);
    }

    private static String bounded(String text) {
        List<String> lines = text.lines().toList();
        StringBuilder bounded = new StringBuilder();
        for (int index = 0; index < Math.min(lines.size(), CODE_LINES); index++) {
            String line = lines.get(index);
            if (index > 0) bounded.append('\n');
            bounded.append(line.length() > CODE_COLUMNS ? line.substring(0, CODE_COLUMNS - 1) + "…" : line);
        }
        if (lines.size() > CODE_LINES) bounded.append("\n… ").append(lines.size() - CODE_LINES).append(" more lines");
        return bounded.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String hex(Color color) {
        return String.format(Locale.ROOT, "#%06X", color.getRGB() & 0xFFFFFF);
    }
}
