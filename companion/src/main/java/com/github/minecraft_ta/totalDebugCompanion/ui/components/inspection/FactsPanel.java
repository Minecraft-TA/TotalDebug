package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.SectionHeading;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.LinkLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectIcons;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactData;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Presents the fact sections of one or more script results under collapsible headings, with one label column aligned
 * across all sections: slot grids, bars with their amounts, colored values, links to classes, and a summary of each
 * data fact. The slots a side of a block exposes form a row of their own, each marked with what it takes and gives.
 * A section a project script reported names the script on its heading. A newer read updates sections of the same
 * shape in place, marking changed values, and rebuilds only the sections whose shape changed.
 */
public final class FactsPanel extends JPanel {
    /** Data up to this size shows its shape in its row. */
    static final int SHAPED_DATA_BYTES = 64 * 1_024;
    static final int SLOT_COLUMNS = 9;
    private static final int ICON_SIZE = UiMetrics.previewPixels(UiMetrics.ITEM_ICON_SIZE);
    private static final int SLOT_SIZE = ICON_SIZE + 8;
    private static final int MIN_LABEL_WIDTH = 96;
    private static final int MAX_LABEL_WIDTH = 240;
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[bBsSlLfFdD%]?");

    /** What clicking a fact does, decided by the panel's owner. */
    public interface Actions {
        Actions NONE = link -> { };

        void open(FactLink link);

        /** Shows the data fact named {@code name}, its section's name and its label, in the data view. */
        default void openData(String name) {
        }

        default void openScript(Path script) {
        }
    }

    /** A project script that reported a section; the section's heading names the script and opens it. */
    public record Origin(String name, Path script) {
        public Origin {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(script, "script");
        }
    }

    /** A section, with the script that reported it or null for the page's own. */
    public record Part(FactSection section, Origin origin) {
        public Part {
            Objects.requireNonNull(section, "section");
        }

        /** Names the section among all parts; data facts are named by it too. */
        public String key() {
            return this.origin == null ? this.section.title() : this.origin.name() + " › " + this.section.title();
        }
    }

    private final ItemIconService icons;
    private final Actions actions;
    private final Set<String> collapsed;
    private final List<SectionView> views = new ArrayList<>();
    private int labelWidth = MIN_LABEL_WIDTH;

    public FactsPanel(List<FactSection> sections, ItemIconService icons) {
        this(sections, icons, Actions.NONE);
    }

    public FactsPanel(List<FactSection> sections, ItemIconService icons, Actions actions) {
        this(parts(sections), icons, actions, new HashSet<>());
    }

    /** {@code collapsed} holds the keys of collapsed sections; the owner keeps it across rebuilt panels. */
    public FactsPanel(List<Part> parts, ItemIconService icons, Actions actions, Set<String> collapsed) {
        this.icons = Objects.requireNonNull(icons, "icons");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.collapsed = Objects.requireNonNull(collapsed, "collapsed");
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(UiMetrics.pagePadding(4, 8));
        for (Part part : parts) {
            SectionView view = new SectionView(part);
            this.views.add(view);
            add(view);
        }
        add(Box.createVerticalGlue());
        alignLabels();
        reloadIcons();
    }

    /** Sections that are the page's own. */
    public static List<Part> parts(List<FactSection> sections) {
        return sections.stream().map(section -> new Part(section, null)).toList();
    }

    /**
     * Applies a newer read. Sections with the same shape update in place; a section whose facts changed shape is
     * rebuilt on its own. Returns false when the sections themselves differ and the panel must be rebuilt.
     */
    public boolean update(List<Part> next) {
        if (!this.views.stream().map(view -> view.key).toList().equals(next.stream().map(Part::key).toList())) {
            return false;
        }
        for (int index = 0; index < next.size(); index++) {
            if (!this.views.get(index).update(next.get(index))) rebuild(index, next.get(index));
        }
        alignLabels();
        revalidate();
        repaint();
        return true;
    }

    /** Draws slot icons and fluid textures again, for example after the game published a newer snapshot. */
    public void reloadIcons() {
        this.views.forEach(SectionView::reloadIcons);
    }

    private void rebuild(int index, Part part) {
        SectionView rebuilt = new SectionView(part);
        remove(index);
        add(rebuilt, index);
        this.views.set(index, rebuilt);
        rebuilt.reloadIcons();
    }

    static boolean sameShape(FactSection before, FactSection after) {
        if (!before.title().equals(after.title()) || before.totalFacts() != after.totalFacts()
                || before.facts().size() != after.facts().size()) {
            return false;
        }
        for (int index = 0; index < before.facts().size(); index++) {
            Fact previous = before.facts().get(index);
            Fact next = after.facts().get(index);
            // A stack with a transfer sits in a side row, one without in the grid.
            if (previous.kind() != next.kind() || !previous.label().equals(next.label())
                    || !Objects.equals(previous.link(), next.link())
                    || (previous.transfer() == null) != (next.transfer() == null)) {
                return false;
            }
        }
        return true;
    }

    /** Gives every section's labels the width of the widest, so values line up across sections. */
    private void alignLabels() {
        int width = MIN_LABEL_WIDTH;
        for (SectionView view : this.views) {
            for (JLabel label : view.labels) {
                label.setPreferredSize(null);
                width = Math.max(width, label.getPreferredSize().width);
            }
        }
        width = Math.min(width, MAX_LABEL_WIDTH);
        this.labelWidth = width;
        for (SectionView view : this.views) {
            for (JLabel label : view.labels) {
                label.setPreferredSize(new Dimension(width, label.getPreferredSize().height));
                label.setMinimumSize(label.getPreferredSize());
            }
        }
    }

    /** The width of the label column, so rows beside the panel can line up with it. */
    public int labelWidth() {
        return this.labelWidth;
    }

    static String amounts(long amount, long capacity, String unit) {
        NumberFormat format = NumberFormat.getIntegerInstance();
        String suffix = unit.isEmpty() ? "" : " " + unit;
        return capacity > 0
                ? format.format(amount) + " / " + format.format(capacity) + suffix
                : format.format(amount) + suffix;
    }

    /**
     * A row's text: the value, or for data a summary of its shape and size. Rows are built on the Swing thread, so data
     * larger than {@link #SHAPED_DATA_BYTES} is summarized by its size alone; the Data tab decodes it off that thread.
     */
    static String displayedValue(Fact fact) {
        if (fact.kind() != Fact.Kind.DATA) {
            return fact.value();
        }
        FactData data = fact.data();
        if (data.size() > SHAPED_DATA_BYTES) return size(data);
        String shape;
        try {
            shape = switch (data.tag()) {
                case NbtData.CompoundTag compound -> count(compound.entries().size(), "key", "keys");
                case NbtData.ListTag list -> count(list.items().size(), "entry", "entries");
                case NbtData.Tag other -> other.typeName();
            };
        } catch (IllegalArgumentException unreadable) {
            return "Unreadable data: " + unreadable.getMessage();
        }
        return shape + ", " + size(data);
    }

    private static String size(FactData data) {
        String size = data.size() < 1_024 ? data.size() + " B"
                : String.format(Locale.ROOT, "%.1f KB", data.size() / 1_024.0);
        return size + (data.complete() ? "" : ", incomplete");
    }

    private static String count(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    /** The color a plain value is shown in: numbers like code, everything else as text. */
    static Color valueColor(String value) {
        return NUMBER.matcher(value).matches() ? ThemeManager.palette().number() : ThemeColors.text();
    }

    /** One section: its heading, which collapses it, and its rows. */
    private final class SectionView extends JPanel {
        private final String key;
        private Part part;
        private final JPanel body = new JPanel(new GridBagLayout());
        private final List<JLabel> labels = new ArrayList<>();
        private final List<SlotCell> slots = new ArrayList<>();
        private final List<JComponent> values = new ArrayList<>();
        private SectionHeading heading;

        private SectionView(Part part) {
            super(new BorderLayout());
            this.key = part.key();
            this.part = part;
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
            add(header(part.section()), BorderLayout.NORTH);
            this.body.setBorder(UiMetrics.sectionBodyPadding());
            add(this.body, BorderLayout.CENTER);
            build(part.section());
            showCollapsed();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        /** Updates the rows in place when the newer read has the same shape; returns false when it must be rebuilt. */
        private boolean update(Part next) {
            if (!Objects.equals(this.part.origin(), next.origin()) || !sameShape(this.part.section(), next.section())) {
                return false;
            }
            updateRows(this.part.section(), next.section());
            this.part = next;
            return true;
        }

        private JComponent header(FactSection section) {
            JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
            if (section.omittedFacts() > 0) {
                JLabel omitted = new JLabel(section.omittedFacts() + " more not shown");
                ThemeColors.keepForeground(omitted, ThemeColors::mutedText);
                trailing.add(omitted);
            }
            Origin origin = this.part.origin();
            if (origin != null) {
                String name = origin.name().equals(section.title()) ? "" : origin.name();
                LinkLabel script = new LinkLabel(name, Icons.SCRIPT_FILE,
                        Tooltip.of("Open Script").detail(Tooltip.shortPath(origin.script())).html(),
                        () -> FactsPanel.this.actions.openScript(origin.script()));
                script.getAccessibleContext().setAccessibleName("Open Script " + origin.name());
                trailing.add(script);
            }
            this.heading = new SectionHeading(section.title(),
                    trailing.getComponentCount() == 0 ? null : trailing, () -> {
                if (!FactsPanel.this.collapsed.remove(this.key)) {
                    FactsPanel.this.collapsed.add(this.key);
                }
                showCollapsed();
            });
            return this.heading;
        }

        private void showCollapsed() {
            boolean collapsed = FactsPanel.this.collapsed.contains(this.key);
            this.heading.setCollapsed(collapsed);
            this.body.setVisible(!collapsed);
            revalidate();
        }

        /**
         * The contents' slots first as one grid, then the other rows in order; the slots of one side, which share its
         * label, form one row. Slot cells are kept in fact order, as updates find them.
         */
        private void build(FactSection section) {
            int row = 0;
            List<SlotCell> contents = new ArrayList<>();
            for (Fact fact : section.facts()) {
                if (fact.kind() != Fact.Kind.STACK) continue;
                SlotCell cell = new SlotCell(fact);
                this.slots.add(cell);
                if (fact.transfer() == null) contents.add(cell);
            }
            if (!contents.isEmpty()) addSlotRow(row++, "", slotGrid(contents));
            List<Fact> facts = section.facts();
            int slot = 0;
            for (int index = 0; index < facts.size(); index++) {
                Fact fact = facts.get(index);
                if (fact.kind() != Fact.Kind.STACK) {
                    JComponent value = value(fact);
                    this.values.add(value);
                    addRow(row++, fact.label(), value);
                    continue;
                }
                SlotCell cell = this.slots.get(slot++);
                if (fact.transfer() == null) continue;
                List<SlotCell> side = new ArrayList<>(List.of(cell));
                while (index + 1 < facts.size() && facts.get(index + 1).transfer() != null
                        && facts.get(index + 1).label().equals(fact.label())) {
                    index++;
                    side.add(this.slots.get(slot++));
                }
                addSlotRow(row++, fact.label(), slotGrid(side));
            }
            GridBagConstraints filler = new GridBagConstraints();
            filler.gridy = row;
            filler.gridx = 2;
            filler.weightx = 1;
            this.body.add(Box.createHorizontalGlue(), filler);
        }

        private static JComponent slotGrid(List<SlotCell> cells) {
            JPanel grid = new JPanel(new GridLayout(0, Math.min(SLOT_COLUMNS, cells.size()), 2, 2));
            cells.forEach(grid::add);
            JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            wrapper.add(grid);
            return wrapper;
        }

        /** A row of slot cells, labelled level with its first row of cells. */
        private void addSlotRow(int row, String text, JComponent slots) {
            JLabel label = addRow(row, text, slots);
            GridBagLayout layout = (GridBagLayout) this.body.getLayout();
            GridBagConstraints labelConstraints = layout.getConstraints(label);
            labelConstraints.anchor = GridBagConstraints.NORTHWEST;
            labelConstraints.insets = new Insets(3 + Math.max(0, (SLOT_SIZE - label.getPreferredSize().height) / 2), 0, 3, 12);
            layout.setConstraints(label, labelConstraints);
            GridBagConstraints slotConstraints = layout.getConstraints(slots);
            slotConstraints.anchor = GridBagConstraints.NORTHWEST;
            layout.setConstraints(slots, slotConstraints);
        }

        private JLabel addRow(int row, String text, JComponent value) {
            JLabel label = new JLabel(text);
            ThemeColors.keepForeground(label, ThemeColors::secondaryText);
            label.setToolTipText(text.isEmpty() ? null : text);
            this.labels.add(label);
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridy = row;
            constraints.gridx = 0;
            constraints.anchor = GridBagConstraints.WEST;
            constraints.insets = new Insets(3, 0, 3, 12);
            this.body.add(label, constraints);
            constraints.gridx = 1;
            constraints.insets = new Insets(3, 0, 3, 0);
            this.body.add(value, constraints);
            return label;
        }

        private JComponent value(Fact fact) {
            return switch (fact.kind()) {
                case BAR, FLUID -> new AmountRow(fact);
                case DATA -> new DataRow(this.key, fact);
                case PROBLEM -> {
                    JLabel problem = new JLabel("", Icons.ERROR, JLabel.LEADING);
                    showProblem(problem, fact.value());
                    ThemeColors.keepForeground(problem, ThemeColors::error);
                    yield problem;
                }
                case TEXT, STACK -> new ValueLabel(fact);
            };
        }

        private void updateRows(FactSection before, FactSection after) {
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
                switch (this.values.get(value++)) {
                    case ValueLabel label -> label.set(next, changed);
                    case AmountRow amount -> amount.set(next, changed);
                    case DataRow data -> data.set(next, changed);
                    case JLabel problem -> showProblem(problem, next.value());
                    default -> {
                    }
                }
            }
        }

        private void reloadIcons() {
            this.slots.forEach(SlotCell::load);
            this.values.forEach(value -> {
                if (value instanceof AmountRow amount) amount.loadTexture();
            });
        }
    }

    /** A problem's first line, with all of it in the tooltip. */
    private static void showProblem(JLabel label, String problem) {
        label.setText(problem.lines().findFirst().orElse(""));
        label.setToolTipText(problem.lines().count() > 1 ? Tooltip.of("").code(problem).html()
                : Tooltip.of("").text(problem).html());
    }

    /** ↓ takes more, ↑ gives, ↕ both, × neither, or nothing where the slot's state cannot tell. */
    static String transferMark(Fact.Transfer transfer) {
        if (transfer == null || transfer.gives() == null) return "";
        boolean takes = Boolean.TRUE.equals(transfer.takes());
        if (takes && transfer.gives()) return "↕";
        if (takes) return "↓";
        if (transfer.gives()) return "↑";
        return transfer.takes() == null ? "" : "×";
    }

    /** Where a linked value leads: a class's source or another subject to inspect. */
    private static String linkTooltip(FactLink link) {
        return switch (link.kind()) {
            case CLASS -> Tooltip.of("Open Source").detail(link.target()).html();
            case SUBJECT -> Tooltip.of("Open").detail(link.target()).html();
        };
    }

    /**
     * A text value colored by what it is; a linked value opens its target when clicked. A value of several lines shows
     * its first, with all of them in the tooltip.
     */
    final class ValueLabel extends JLabel {
        private Fact fact;
        private boolean hovered;

        private ValueLabel(Fact fact) {
            setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
            setBackground(ChangeMarks.tint());
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (ValueLabel.this.fact.link() != null && SwingUtilities.isLeftMouseButton(event)) {
                        FactsPanel.this.actions.open(ValueLabel.this.fact.link());
                    }
                }

                @Override
                public void mouseEntered(MouseEvent event) {
                    hover(true);
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover(false);
                }
            });
            set(fact, false);
        }

        private void set(Fact next, boolean changed) {
            this.fact = next;
            boolean lines = next.value().lines().count() > 1;
            setText(lines ? next.value().lines().findFirst().orElse("") : next.value());
            setOpaque(changed);
            boolean linked = next.link() != null;
            setIcon(linked ? SubjectIcons.link(next.link()) : null);
            setCursor(linked ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            setToolTipText(linked ? linkTooltip(next.link())
                    : lines ? Tooltip.of("").code(next.value()).html()
                    : next.value().length() > 40 ? Tooltip.of("").text(next.value()).html() : null);
            hover(this.hovered);
        }

        private void hover(boolean hovered) {
            this.hovered = hovered;
            boolean linked = this.fact.link() != null;
            setForeground(linked ? ThemeColors.link() : valueColor(this.fact.value()));
            Map<TextAttribute, Object> attributes = new HashMap<>();
            attributes.put(TextAttribute.UNDERLINE, linked && hovered ? TextAttribute.UNDERLINE_ON : -1);
            setFont(UIManager.getFont("Label.font").deriveFont(attributes));
            repaint();
        }

        Fact fact() {
            return this.fact;
        }

        /** A restyle resets the foreground to the default text color; the link color and font come back here. */
        @Override
        public void updateUI() {
            super.updateUI();
            if (this.fact != null) hover(this.hovered);
        }
    }

    /** A bar with its amount beside it, and for fluids the fluid's name and texture. */
    final class AmountRow extends JPanel {
        private final AmountBar bar;
        private final JLabel name = new JLabel();
        private final JLabel amount = new JLabel();
        private Fact fact;

        private AmountRow(Fact fact) {
            super(new FlowLayout(FlowLayout.LEFT, 0, 0));
            this.bar = new AmountBar(fact.amount(), fact.capacity());
            ThemeColors.keepForeground(this.amount, ThemeColors::mutedText);
            add(this.bar);
            add(Box.createHorizontalStrut(10));
            add(this.name);
            add(Box.createHorizontalStrut(8));
            add(this.amount);
            this.fact = fact;
            describe();
        }

        private void set(Fact next, boolean changed) {
            boolean fluidChanged = !next.id().equals(this.fact.id());
            this.fact = next;
            this.bar.set(next.amount(), next.capacity(), changed);
            describe();
            if (fluidChanged) loadTexture();
        }

        private void describe() {
            if (this.fact.kind() == Fact.Kind.FLUID) {
                this.name.setText(this.fact.id().isEmpty() ? "Empty" : this.fact.value());
                ThemeColors.keepForeground(this.name, this.fact.id().isEmpty() ? ThemeColors::mutedText : ThemeColors::text);
                this.amount.setText(amounts(this.fact.amount(), this.fact.capacity(), this.fact.unit()));
                this.name.setToolTipText(this.fact.id().isEmpty() ? null : this.fact.id());
            } else {
                this.name.setText(amounts(this.fact.amount(), this.fact.capacity(), this.fact.unit()));
                ThemeColors.keepForeground(this.name, ThemeColors::text);
                this.amount.setText(this.fact.capacity() > 0
                        ? Math.round(this.bar.fraction() * 100) + "%" : "");
            }
        }

        private void loadTexture() {
            if (this.fact.kind() != Fact.Kind.FLUID || this.fact.id().isEmpty()) {
                this.bar.setTexture(null);
                return;
            }
            String fluid = this.fact.id();
            FactsPanel.this.icons.fluidTexture(fluid).thenAccept(texture -> SwingUtilities.invokeLater(() -> {
                if (fluid.equals(this.fact.id())) this.bar.setTexture(texture.orElse(null));
            }));
        }

        String text() {
            return (this.name.getText() + " " + this.amount.getText()).strip();
        }

        boolean changed() {
            return this.bar.changed();
        }
    }

    /** A data fact's shape and size, with a link that shows it in the data view. */
    private final class DataRow extends JPanel {
        private final JLabel summary = new JLabel();

        private DataRow(String sectionKey, Fact fact) {
            super(new FlowLayout(FlowLayout.LEFT, 0, 0));
            this.summary.setBackground(ChangeMarks.tint());
            ThemeColors.keepForeground(this.summary, ThemeColors::text);
            JLabel open = new JLabel("Open in Data");
            ThemeColors.keepForeground(open, ThemeColors::link);
            open.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            open.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    FactsPanel.this.actions.openData(sectionKey + " › " + fact.label());
                }
            });
            add(this.summary);
            add(Box.createHorizontalStrut(12));
            add(open);
            set(fact, false);
        }

        private void set(Fact next, boolean changed) {
            this.summary.setText(displayedValue(next));
            this.summary.setOpaque(changed);
            this.summary.repaint();
        }
    }

    /**
     * One inventory slot: its item icon with the stack count, or an empty frame. A slot a side exposes is marked with
     * what it does: ↓ takes more, ↑ gives, ↕ both, × neither; the tooltip says what its state cannot tell.
     */
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
            Tooltip tooltip = this.stack.id().isEmpty()
                    ? Tooltip.of("Empty").detail(this.stack.label())
                    : Tooltip.of(this.stack.value() + " ×" + this.stack.amount()).detail(this.stack.id()).detail(this.stack.label());
            Fact.Transfer transfer = this.stack.transfer();
            if (transfer != null && !this.stack.id().isEmpty()) {
                tooltip.fact("Takes more", transfer.takes() == null ? "Cannot tell while full" : transfer.takes() ? "Yes" : "No")
                        .fact("Gives", transfer.gives() == null ? "Cannot tell" : transfer.gives() ? "Yes" : "No");
            } else if (transfer != null) {
                tooltip.text("An empty slot cannot tell what it takes");
            }
            setToolTipText(tooltip.html());
        }

        private void load() {
            if (this.stack.id().isEmpty()) {
                return;
            }
            String id = this.stack.id();
            CatalogIndex.ItemIcon item = FactsPanel.this.icons.itemIcon(id);
            FactsPanel.this.icons.render(item.model(), item.tints(), ICON_SIZE)
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
                paintTransfer(g);
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

        /** The mark of what the slot does through its side, on a badge in the top left corner. */
        private void paintTransfer(Graphics2D g) {
            String mark = transferMark(this.stack.transfer());
            if (mark.isEmpty()) return;
            g.setFont(getFont().deriveFont(getFont().getSize2D() - 1));
            int width = g.getFontMetrics().stringWidth(mark) + 4;
            int height = g.getFontMetrics().getHeight();
            g.setColor(UIManager.getColor("Panel.background"));
            g.fillRoundRect(1, 1, width, height, 4, 4);
            g.setColor(ThemeColors.link());
            g.drawString(mark, 3, 1 + g.getFontMetrics().getAscent());
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
