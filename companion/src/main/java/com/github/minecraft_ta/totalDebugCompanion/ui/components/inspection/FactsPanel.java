package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Presents the fact sections of one script result: slot grids, bars and label/value rows. */
public final class FactsPanel extends JPanel {
    static final int SLOT_COLUMNS = 9;
    private static final int SLOT_SIZE = 40;
    private static final int ICON_SIZE = 32;

    private final ItemIconService icons;
    private final List<SlotCell> slots = new ArrayList<>();

    public FactsPanel(List<FactSection> sections, ItemIconService icons) {
        this.icons = Objects.requireNonNull(icons, "icons");
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        if (sections.isEmpty()) {
            add(aligned(new JLabel("No sections reported")));
        }
        for (FactSection section : sections) {
            add(aligned(title(section)));
            add(Box.createVerticalStrut(4));
            add(aligned(content(section)));
            add(Box.createVerticalStrut(12));
        }
        add(Box.createVerticalGlue());
        reloadIcons();
    }

    /** Draws slot icons again, for example after the game published a newer resource snapshot. */
    public void reloadIcons() {
        for (SlotCell slot : this.slots) {
            slot.load();
        }
    }

    private static JLabel title(FactSection section) {
        String text = section.title();
        if (section.omittedFacts() > 0) {
            text += "  (" + section.omittedFacts() + " more not shown)";
        }
        JLabel title = new JLabel(text);
        title.putClientProperty("FlatLaf.styleClass", "h4");
        return title;
    }

    private JComponent content(FactSection section) {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        List<Fact> stacks = section.facts().stream().filter(fact -> fact.kind() == Fact.Kind.STACK).toList();
        if (!stacks.isEmpty()) {
            content.add(aligned(slotGrid(stacks)));
        }
        List<Fact> rows = section.facts().stream().filter(fact -> fact.kind() != Fact.Kind.STACK).toList();
        if (!rows.isEmpty()) {
            if (!stacks.isEmpty()) {
                content.add(Box.createVerticalStrut(6));
            }
            content.add(aligned(rows(rows)));
        }
        return content;
    }

    private JComponent slotGrid(List<Fact> stacks) {
        JPanel grid = new JPanel(new GridLayout(0, Math.min(SLOT_COLUMNS, stacks.size()), 2, 2));
        for (Fact stack : stacks) {
            SlotCell cell = new SlotCell(stack);
            this.slots.add(cell);
            grid.add(cell);
        }
        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrapper.add(grid);
        return wrapper;
    }

    private static JComponent rows(List<Fact> facts) {
        JPanel rows = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(2, 0, 2, 12);
        constraints.anchor = GridBagConstraints.WEST;
        for (int row = 0; row < facts.size(); row++) {
            Fact fact = facts.get(row);
            constraints.gridy = row;
            constraints.gridx = 0;
            constraints.weightx = 0;
            constraints.fill = GridBagConstraints.NONE;
            JLabel label = new JLabel(fact.label());
            label.setForeground(UIManager.getColor("Label.disabledForeground"));
            rows.add(label, constraints);
            constraints.gridx = 1;
            constraints.weightx = 1;
            rows.add(value(fact), constraints);
        }
        return rows;
    }

    private static JComponent value(Fact fact) {
        return switch (fact.kind()) {
            case TEXT, STACK -> {
                JLabel value = new JLabel(fact.value());
                value.setToolTipText(fact.value());
                yield value;
            }
            case BAR -> bar(fact.amount(), fact.capacity(), amounts(fact.amount(), fact.capacity(), fact.unit()));
            case FLUID -> bar(fact.amount(), fact.capacity(), fact.id().isEmpty()
                    ? "Empty  ·  " + amounts(0, fact.capacity(), fact.unit())
                    : fact.value() + "  ·  " + amounts(fact.amount(), fact.capacity(), fact.unit()));
        };
    }

    private static JProgressBar bar(long amount, long capacity, String text) {
        JProgressBar bar = new JProgressBar(0, 1_000);
        bar.setValue(capacity <= 0 ? 0 : (int) Math.min(1_000, amount * 1_000 / capacity));
        bar.setStringPainted(true);
        bar.setString(text);
        bar.setPreferredSize(new Dimension(320, bar.getPreferredSize().height + 4));
        return bar;
    }

    static String amounts(long amount, long capacity, String unit) {
        NumberFormat format = NumberFormat.getIntegerInstance();
        String suffix = unit.isEmpty() ? "" : " " + unit;
        return capacity > 0
                ? format.format(amount) + " / " + format.format(capacity) + suffix
                : format.format(amount) + suffix;
    }

    private static <T extends JComponent> T aligned(T component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    /** One inventory slot: its item icon with the stack count, or an empty frame. */
    private final class SlotCell extends JComponent {
        private final Fact stack;
        private BufferedImage icon;

        private SlotCell(Fact stack) {
            this.stack = stack;
            Dimension size = new Dimension(SLOT_SIZE, SLOT_SIZE);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
            setToolTipText(stack.id().isEmpty()
                    ? stack.label() + ": empty"
                    : stack.label() + ": " + stack.value() + " ×" + stack.amount() + "  (" + stack.id() + ")");
        }

        private void load() {
            if (this.stack.id().isEmpty()) {
                return;
            }
            FactsPanel.this.icons.render(ItemIconService.itemModel(this.stack.id()), Map.of(), ICON_SIZE)
                    .thenAccept(image -> SwingUtilities.invokeLater(() -> {
                        this.icon = image.orElse(null);
                        repaint();
                    }));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(UIManager.getColor("TextField.background"));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                g.setColor(UIManager.getColor("Component.borderColor"));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                if (this.stack.id().isEmpty()) {
                    return;
                }
                int inset = (getWidth() - ICON_SIZE) / 2;
                if (this.icon != null) {
                    g.drawImage(this.icon, inset, inset, ICON_SIZE, ICON_SIZE, null);
                } else {
                    paintFallback(g, inset);
                }
                if (this.stack.amount() > 1) {
                    paintCount(g);
                }
            } finally {
                g.dispose();
            }
        }

        /** Shows the item's initials while no icon can be drawn. */
        private void paintFallback(Graphics2D g, int inset) {
            String path = this.stack.id().substring(this.stack.id().indexOf(':') + 1);
            String initials = path.isEmpty() ? "?" : path.substring(0, Math.min(2, path.length())).toUpperCase();
            g.setColor(UIManager.getColor("Label.disabledForeground"));
            g.setFont(getFont().deriveFont(Font.BOLD));
            int width = g.getFontMetrics().stringWidth(initials);
            g.drawString(initials, (getWidth() - width) / 2, inset + ICON_SIZE / 2 + g.getFontMetrics().getAscent() / 2);
        }

        private void paintCount(Graphics2D g) {
            String count = this.stack.amount() > 9_999
                    ? (this.stack.amount() / 1_000) + "k"
                    : Long.toString(this.stack.amount());
            g.setFont(getFont().deriveFont(Font.BOLD, getFont().getSize2D() - 1));
            int x = getWidth() - 3 - g.getFontMetrics().stringWidth(count);
            int y = getHeight() - 3;
            g.setColor(new Color(0, 0, 0, 170));
            g.drawString(count, x + 1, y + 1);
            g.setColor(Color.WHITE);
            g.drawString(count, x, y);
        }
    }
}
