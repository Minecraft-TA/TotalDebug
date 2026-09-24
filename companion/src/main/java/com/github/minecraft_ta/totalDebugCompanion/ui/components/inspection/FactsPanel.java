package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BasicStroke;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Presents the fact sections of one script result: slot grids, bars and label/value rows, and trees for nested facts.
 * A newer read with the same shape updates in place and marks the values that changed.
 */
public final class FactsPanel extends JPanel {
    static final int SLOT_COLUMNS = 9;
    private static final int SLOT_SIZE = 40;
    private static final int ICON_SIZE = 32;

    private final ItemIconService icons;
    private final List<SectionView> views = new ArrayList<>();
    private List<FactSection> sections;

    public FactsPanel(List<FactSection> sections, ItemIconService icons) {
        this(sections, icons, Map.of());
    }

    /** Builds the panel, expanding tree sections as they were expanded in {@code expanded} (keyed by title). */
    FactsPanel(List<FactSection> sections, ItemIconService icons, Map<String, Set<List<String>>> expanded) {
        this.icons = Objects.requireNonNull(icons, "icons");
        this.sections = List.copyOf(sections);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        if (sections.isEmpty()) {
            add(aligned(new JLabel("No sections reported")));
        }
        for (FactSection section : sections) {
            SectionView view = new SectionView(section, expanded.get(section.title()));
            this.views.add(view);
            add(aligned(title(section)));
            add(Box.createVerticalStrut(4));
            add(aligned(view.content));
            add(Box.createVerticalStrut(12));
        }
        add(Box.createVerticalGlue());
        reloadIcons();
    }

    /** Applies a newer read in place. Returns false when its shape differs and the panel must be rebuilt. */
    public boolean update(List<FactSection> next) {
        if (!sameShape(this.sections, next)) {
            return false;
        }
        for (int index = 0; index < next.size(); index++) {
            this.views.get(index).update(this.sections.get(index), next.get(index));
        }
        this.sections = List.copyOf(next);
        return true;
    }

    /** Tree expansion by section title, for carrying over into a rebuilt panel. */
    Map<String, Set<List<String>>> expandedTrees() {
        Map<String, Set<List<String>>> expanded = new HashMap<>();
        for (int index = 0; index < this.views.size(); index++) {
            if (this.views.get(index).tree != null) {
                expanded.put(this.sections.get(index).title(), this.views.get(index).tree.expandedLabels());
            }
        }
        return expanded;
    }

    /** Draws slot icons and fluid textures again, for example after the game published a newer snapshot. */
    public void reloadIcons() {
        for (SectionView view : this.views) {
            view.slots.forEach(SlotCell::load);
            view.values.forEach(value -> {
                if (value instanceof AmountBar bar && view.fluids.containsKey(bar)) {
                    String fluid = view.fluids.get(bar);
                    this.icons.fluidTexture(fluid).thenAccept(texture ->
                            SwingUtilities.invokeLater(() -> bar.setTexture(texture.orElse(null))));
                }
            });
        }
    }

    static boolean sameShape(List<FactSection> previous, List<FactSection> next) {
        if (previous.size() != next.size()) return false;
        for (int index = 0; index < previous.size(); index++) {
            FactSection before = previous.get(index);
            FactSection after = next.get(index);
            if (!before.title().equals(after.title()) || before.totalFacts() != after.totalFacts()
                    || !sameFacts(before.facts(), after.facts())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameFacts(List<Fact> previous, List<Fact> next) {
        if (previous.size() != next.size()) return false;
        for (int index = 0; index < previous.size(); index++) {
            Fact before = previous.get(index);
            Fact after = next.get(index);
            if (before.kind() != after.kind() || !before.label().equals(after.label())
                    || before.totalChildren() != after.totalChildren()
                    || !sameFacts(before.children(), after.children())) {
                return false;
            }
        }
        return true;
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

    static String amounts(long amount, long capacity, String unit) {
        NumberFormat format = NumberFormat.getIntegerInstance();
        String suffix = unit.isEmpty() ? "" : " " + unit;
        return capacity > 0
                ? format.format(amount) + " / " + format.format(capacity) + suffix
                : format.format(amount) + suffix;
    }

    private static String barText(Fact fact) {
        if (fact.kind() == Fact.Kind.BAR) {
            return amounts(fact.amount(), fact.capacity(), fact.unit());
        }
        return fact.id().isEmpty()
                ? "Empty  ·  " + amounts(0, fact.capacity(), fact.unit())
                : fact.value() + "  ·  " + amounts(fact.amount(), fact.capacity(), fact.unit());
    }

    private static <T extends JComponent> T aligned(T component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    /** The components presenting one section, in the order of its facts. */
    private final class SectionView {
        private final JPanel content = new JPanel();
        private final List<SlotCell> slots = new ArrayList<>();
        private final List<JComponent> values = new ArrayList<>();
        private final Map<AmountBar, String> fluids = new HashMap<>();
        private FactTree tree;

        private SectionView(FactSection section, Set<List<String>> expanded) {
            this.content.setLayout(new BoxLayout(this.content, BoxLayout.Y_AXIS));
            if (section.facts().stream().anyMatch(fact -> fact.totalChildren() > 0)) {
                this.tree = FactTree.of(section.facts(), expanded);
                this.content.add(aligned(this.tree));
                return;
            }
            List<Fact> stacks = section.facts().stream().filter(fact -> fact.kind() == Fact.Kind.STACK).toList();
            if (!stacks.isEmpty()) {
                JPanel grid = new JPanel(new GridLayout(0, Math.min(SLOT_COLUMNS, stacks.size()), 2, 2));
                for (Fact stack : stacks) {
                    SlotCell cell = new SlotCell(stack);
                    this.slots.add(cell);
                    grid.add(cell);
                }
                JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                wrapper.add(grid);
                this.content.add(aligned(wrapper));
            }
            List<Fact> rows = section.facts().stream().filter(fact -> fact.kind() != Fact.Kind.STACK).toList();
            if (!rows.isEmpty()) {
                if (!stacks.isEmpty()) {
                    this.content.add(Box.createVerticalStrut(6));
                }
                this.content.add(aligned(rows(rows)));
            }
        }

        private JComponent rows(List<Fact> facts) {
            JPanel rows = new JPanel(new GridBagLayout());
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.insets = new Insets(2, 0, 2, 12);
            constraints.anchor = GridBagConstraints.WEST;
            for (int row = 0; row < facts.size(); row++) {
                Fact fact = facts.get(row);
                constraints.gridy = row;
                constraints.gridx = 0;
                constraints.weightx = 0;
                JLabel label = new JLabel(fact.label());
                label.setForeground(UIManager.getColor("Label.disabledForeground"));
                rows.add(label, constraints);
                constraints.gridx = 1;
                constraints.weightx = 1;
                JComponent value = value(fact);
                this.values.add(value);
                rows.add(value, constraints);
            }
            return rows;
        }

        private JComponent value(Fact fact) {
            if (fact.kind() == Fact.Kind.PROBLEM) {
                JLabel problem = new JLabel(fact.value(), Icons.ERROR, JLabel.LEADING);
                problem.setToolTipText(fact.value());
                problem.setForeground(ThemeColors.error());
                problem.setBackground(ChangeMarks.tint());
                return problem;
            }
            if (fact.kind() == Fact.Kind.TEXT) {
                JLabel value = new JLabel(fact.value());
                value.setToolTipText(fact.value());
                value.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
                value.setBackground(ChangeMarks.tint());
                return value;
            }
            AmountBar bar = new AmountBar(fact.amount(), fact.capacity(), barText(fact));
            if (fact.kind() == Fact.Kind.FLUID && !fact.id().isEmpty()) {
                this.fluids.put(bar, fact.id());
            }
            return bar;
        }

        private void update(FactSection before, FactSection after) {
            if (this.tree != null) {
                this.tree.update(after.facts());
                return;
            }
            int slot = 0;
            int value = 0;
            for (int index = 0; index < after.facts().size(); index++) {
                Fact previous = before.facts().get(index);
                Fact next = after.facts().get(index);
                boolean changed = !previous.equals(next);
                if (next.kind() == Fact.Kind.STACK) {
                    this.slots.get(slot++).set(next, changed);
                    continue;
                }
                JComponent component = this.values.get(value++);
                if (component instanceof JLabel label) {
                    label.setText(next.value());
                    label.setToolTipText(next.value());
                    label.setOpaque(changed);
                    label.repaint();
                } else if (component instanceof AmountBar bar) {
                    bar.set(next.amount(), next.capacity(), barText(next), changed);
                    String fluid = next.kind() == Fact.Kind.FLUID && !next.id().isEmpty() ? next.id() : null;
                    if (!Objects.equals(this.fluids.get(bar), fluid)) {
                        if (fluid == null) {
                            this.fluids.remove(bar);
                            bar.setTexture(null);
                        } else {
                            this.fluids.put(bar, fluid);
                            FactsPanel.this.icons.fluidTexture(fluid).thenAccept(texture ->
                                    SwingUtilities.invokeLater(() -> bar.setTexture(texture.orElse(null))));
                        }
                    }
                }
            }
        }
    }

    /** One inventory slot: its item icon with the stack count, or an empty frame. */
    private final class SlotCell extends JComponent {
        private Fact stack;
        private BufferedImage icon;
        private boolean changed;

        private SlotCell(Fact stack) {
            Dimension size = new Dimension(SLOT_SIZE, SLOT_SIZE);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
            this.stack = stack;
            describe();
        }

        private void set(Fact next, boolean changed) {
            boolean itemChanged = !next.id().equals(this.stack.id());
            this.stack = next;
            this.changed = changed;
            describe();
            if (itemChanged) {
                this.icon = null;
                load();
            }
            repaint();
        }

        private void describe() {
            setToolTipText(this.stack.id().isEmpty()
                    ? this.stack.label() + ": empty"
                    : this.stack.label() + ": " + this.stack.value() + " ×" + this.stack.amount()
                    + "  (" + this.stack.id() + ")");
        }

        private void load() {
            if (this.stack.id().isEmpty()) {
                return;
            }
            String id = this.stack.id();
            FactsPanel.this.icons.render(ItemIconService.itemModel(id), Map.of(), ICON_SIZE)
                    .thenAccept(image -> SwingUtilities.invokeLater(() -> {
                        if (!id.equals(this.stack.id())) return;
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
                if (this.changed) {
                    g.setColor(ChangeMarks.color());
                    g.setStroke(new BasicStroke(2f));
                    g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 6, 6);
                } else {
                    g.setColor(UIManager.getColor("Component.borderColor"));
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                }
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
