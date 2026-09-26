package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.RegistryIds;
import com.github.minecraft_ta.totalDebugCompanion.inspection.InspectionSession;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.SubjectLinks;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService.Side;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.SegmentedToggle;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.DefinitionDetails;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.ContentKinds;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.LinkLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.PlateIcon;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectHeader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.values.ScriptResultTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionValue;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The page of a block, item or entity. Its definition, what the captured catalog says about the registered type, is
 * always shown: an identity section with its classes and related entries, and its Appearance and Files last. A page
 * opened on something in the game also reads it through an {@link InspectionSession}: where it is in the header, its
 * state and the project's tools after the identity, and Data and Java object tabs beside the overview. The header,
 * the tab and the definition follow what occupies the subject, and a replaced block is called out.
 */
public final class SubjectPanel extends JPanel {
    private final DefinitionDetails.Services services;
    private final SubjectHeader header = new SubjectHeader();
    private final ItemTabIcon tabIcon;
    private final JPanel sections = new JPanel();
    private final Set<String> collapsed = new HashSet<>();
    private final Runnable removeIconListener;
    private final Live live;
    private final FactsPanel.Actions actions = new FactsPanel.Actions() {
        @Override
        public void open(FactLink link) {
            try {
                SubjectPanel.this.services.navigator().accept(SubjectLinks.target(link));
            } catch (IllegalArgumentException unsupported) {
                if (SubjectPanel.this.live != null) SubjectPanel.this.live.showProblemNotice(unsupported.getMessage(), unsupported.getMessage());
            }
        }

        @Override
        public void openData(String name) {
            if (SubjectPanel.this.live != null) SubjectPanel.this.live.openData(name);
        }

        @Override
        public void openScript(Path script) {
            SubjectPanel.this.services.navigator().accept(new NavigationTarget.LocalFile(script));
        }
    };
    private DefinitionDetails details;
    private FactsPanel facts;
    private boolean disposed;

    /** The page of a registered block, item or entity type. */
    public static SubjectPanel definition(SubjectRef.Definition subject, DefinitionDetails.Services services) {
        return new SubjectPanel(subject, null, null, null, services);
    }

    /** The page of a block or entity in the game, read live. */
    public static SubjectPanel occurrence(InspectSubjectPayload subject, Supplier<SnippetExecutionService> snippets,
                                          Supplier<ScriptFiles> scripts, DefinitionDetails.Services services) {
        return new SubjectPanel(definition(subject.identity()), Objects.requireNonNull(subject, "subject"),
                Objects.requireNonNull(snippets, "snippets"), Objects.requireNonNull(scripts, "scripts"), services);
    }

    private SubjectPanel(SubjectRef.Definition definition, InspectSubjectPayload subject,
                         Supplier<SnippetExecutionService> snippets, Supplier<ScriptFiles> scripts,
                         DefinitionDetails.Services services) {
        super(new BorderLayout());
        this.services = Objects.requireNonNull(services, "services");
        this.tabIcon = new ItemTabIcon(ContentKinds.of(definition.registry()).icon());
        this.details = new DefinitionDetails(definition, services, this::definitionChanged);
        this.sections.setLayout(new BoxLayout(this.sections, BoxLayout.Y_AXIS));
        JPanel overview = new JPanel(new BorderLayout());
        overview.add(this.sections, BorderLayout.NORTH);
        this.live = subject == null ? null : new Live(subject, snippets, scripts);
        JPanel top = new JPanel(new BorderLayout());
        top.add(this.header, BorderLayout.NORTH);
        if (this.live != null) {
            this.live.addControls();
            top.add(this.live.notices(), BorderLayout.SOUTH);
            add(this.live.content(scroll(overview)), BorderLayout.CENTER);
        } else {
            add(scroll(overview), BorderLayout.CENTER);
        }
        add(top, BorderLayout.NORTH);
        showHeader();
        showSections();
        this.removeIconListener = services.icons().addListener(this::reloadIcons);
        reloadIcons();
    }

    /** The definition of what occupies a subject in the game. */
    static SubjectRef.Definition definition(SubjectIdentity identity) {
        String registry = switch (identity.kind()) {
            case BLOCK -> RegistryIds.BLOCK;
            case ENTITY -> RegistryIds.ENTITY_TYPE;
            case ITEM -> RegistryIds.ITEM;
        };
        return new SubjectRef.Definition(registry, identity.registryId());
    }

    /** The name of what the page shows, for the editor tab. */
    public String title() {
        return this.live == null ? this.details.title() : this.live.identity.title();
    }

    /** The rendered item for the editor tab, with the kind's icon until the item can be drawn. */
    public ItemTabIcon tabIcon() {
        return this.tabIcon;
    }

    /** The reading behind a page opened on something in the game, or null for a definition's page. */
    public InspectionSession session() {
        return this.live == null ? null : this.live.session;
    }

    /** Starts a new read of the subject in the game, replacing one still running; a definition's page has none. */
    public void refresh() {
        if (this.live != null) this.live.session.refresh();
    }

    /** The kind, where the subject is when it is in the game, and the mod it belongs to. */
    private void showHeader() {
        this.header.setTitle(title());
        SubjectRef.Definition definition = this.details.subject();
        List<JComponent> parts = new ArrayList<>();
        parts.add(SubjectHeader.text(ContentKinds.of(definition.registry()).singular()));
        if (this.live != null) parts.addAll(this.live.where());
        String namespace = definition.namespace();
        String mod = this.details.modName();
        // Without a catalog the game's own name for the mod is better than its namespace.
        if (this.live != null && mod.equals(namespace) && !this.live.identity.modName().isBlank()) {
            mod = this.live.identity.modName();
        }
        parts.add(new LinkLabel(mod, Icons.MOD, "mod " + namespace,
                () -> this.services.navigator().accept(new NavigationTarget.ModPage(namespace))));
        this.header.setSubtitle(parts);
    }

    /**
     * The first overview section: what the subject is, its classes and the entries it relates to. A subject in the
     * game names the classes it was read with and links its definition's page.
     */
    static FactSection identitySection(SubjectRef.Definition definition, List<Fact> classes, List<Fact> related,
                                       boolean linkDefinition) {
        List<Fact> facts = new ArrayList<>();
        Fact id = Fact.text("ID", definition.id());
        facts.add(linkDefinition ? id.withLink(FactLink.toSubject(definition)) : id);
        facts.addAll(classes);
        facts.addAll(related);
        return new FactSection(ContentKinds.of(definition.registry()).singular(), facts, facts.size());
    }

    private FactSection identitySection() {
        List<Fact> classes = this.live == null ? this.details.classFact().stream().toList() : this.live.classes();
        return identitySection(this.details.subject(), classes, this.details.related(), this.live != null);
    }

    /** Shows the identity, then the state and tools when read live, updating in place where possible. */
    private void showSections() {
        List<FactsPanel.Part> parts = new ArrayList<>();
        parts.add(new FactsPanel.Part(identitySection(), null));
        if (this.live != null) parts.addAll(this.live.parts());
        if (this.facts != null && this.facts.update(parts)) return;
        this.facts = new FactsPanel(parts, this.services.icons(), this.actions, this.collapsed);
        this.facts.setAlignmentX(Component.LEFT_ALIGNMENT);
        this.sections.removeAll();
        String unavailable = this.live == null ? this.details.unavailable() : "";
        if (!unavailable.isEmpty()) {
            JLabel message = new JLabel(unavailable);
            message.setBorder(UiMetrics.messagePadding());
            message.setAlignmentX(Component.LEFT_ALIGNMENT);
            this.sections.add(message);
        }
        this.sections.add(this.facts);
        JComponent extras = this.details.extras();
        extras.setAlignmentX(Component.LEFT_ALIGNMENT);
        this.sections.add(extras);
        this.sections.revalidate();
        this.sections.repaint();
    }

    /** The catalog changed or the page now shows another definition: header, identity, icons and tab title. */
    private void definitionChanged() {
        if (this.disposed) return;
        showHeader();
        this.facts = null;
        showSections();
        reloadIcons();
        EditorTabs tabs = (EditorTabs) SwingUtilities.getAncestorOfClass(EditorTabs.class, this);
        if (tabs != null) tabs.refreshEditorTitles();
    }

    /** Follows a subject in the game that now holds another definition. */
    private void showDefinition(SubjectRef.Definition definition) {
        if (definition.equals(this.details.subject())) {
            definitionChanged();
            return;
        }
        this.details.dispose();
        this.details = new DefinitionDetails(definition, this.services, this::definitionChanged);
        definitionChanged();
    }

    /**
     * Draws the header's and the tab's item. A subject in the game shows the stack selected in it, with its tints;
     * otherwise, and after a replacement, the definition's item. Without an item the header shows the kind's tile.
     */
    private void reloadIcons() {
        if (this.disposed) return;
        if (this.facts != null) this.facts.reloadIcons();
        String model = "";
        Map<Integer, Integer> tints = Map.of();
        if (this.live != null && this.live.showsSelectedItem()) {
            model = this.live.subject.iconModel();
            tints = this.live.subject.iconTints();
        } else {
            CatalogIndex.ItemIcon icon = this.live != null && !this.live.identity.iconItem().isEmpty()
                    ? this.services.icons().itemIcon(this.live.identity.iconItem())
                    : this.details.icon().orElse(null);
            if (icon != null) {
                model = icon.model();
                tints = icon.tints();
            }
        }
        Icon plate = new PlateIcon(ContentKinds.of(this.details.subject().registry()).icon(), SubjectHeader.ICON_SIZE);
        this.services.icons().render(model, tints, SubjectHeader.ICON_SIZE)
                .thenAccept(image -> SwingUtilities.invokeLater(() -> {
                    if (!this.disposed) this.header.setIcon(image.<Icon>map(ImageIcon::new).orElse(plate));
                }));
        this.services.icons().render(model, tints, this.tabIcon.size())
                .thenAccept(image -> SwingUtilities.invokeLater(() -> {
                    if (this.disposed) return;
                    this.tabIcon.setImage(image.orElse(null));
                    Component tabs = SwingUtilities.getAncestorOfClass(JTabbedPane.class, this);
                    if (tabs != null) tabs.repaint();
                }));
    }

    public void dispose() {
        requireEdt();
        this.disposed = true;
        if (this.live != null) this.live.dispose();
        this.details.dispose();
        this.removeIconListener.run();
    }

    private static JScrollPane scroll(JComponent content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    static String seconds(int millis) {
        return (millis % 1_000 == 0 ? Integer.toString(millis / 1_000) : Double.toString(millis / 1_000.0)) + " s";
    }

    private static String simpleName(String binaryName) {
        return binaryName.substring(binaryName.lastIndexOf('.') + 1);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Subject pages are used on the EDT");
        }
    }

    /**
     * The part of the page that reads a subject in the game: its session and controls, the notices of a replaced
     * subject and a failed read, and the tabs beside the overview. A read that fails keeps the facts it did report,
     * and a failed read keeps the previous one on screen, marked outdated.
     */
    private final class Live {
        private static final String RESULT_CARD = "result";
        private static final String PROBLEM_CARD = "problem";

        private final InspectSubjectPayload subject;
        private final InspectionSession session;
        private final JLabel replaced = new JLabel();
        private final JLabel problemNotice = new JLabel();
        private final SegmentedToggle<Side> runSide = new SegmentedToggle<>(List.of(Side.SERVER, Side.CLIENT),
                side -> side == Side.CLIENT ? "Client" : "Server");
        private final JButton liveButton = new FlatIconButton(Icons.RUN, true);
        private final JButton liveInterval = new JButton();
        private final JButton refresh = new FlatIconButton(Icons.REFRESH, false);
        private final JButton toolsButton = new FlatIconButton(Icons.SCRIPT_FILE, false);
        private final JTabbedPane views = new JTabbedPane();
        private final ScriptResultTree object = new ScriptResultTree();
        private final DataView data = new DataView();
        private final JTextArea problem = new JTextArea();
        private final JPanel cards = new JPanel(new CardLayout());
        private SubjectIdentity identity;
        private InspectionSession.State state;
        private ExecutionValue shownValue;
        private int liveIntervalMs = 1_000;

        private Live(InspectSubjectPayload subject, Supplier<SnippetExecutionService> snippets, Supplier<ScriptFiles> scripts) {
            this.subject = subject;
            this.identity = subject.identity();
            this.session = new InspectionSession(subject, snippets, scripts, this::show, SubjectPanel.this::isShowing);
            this.state = this.session.state();
        }

        /** The side to read, live reading and its interval, reading again, and the tools, at the header's right. */
        private void addControls() {
            this.runSide.setToolTipText(Side.SERVER, "Read the server's copy of the world");
            this.runSide.setToolTipText(Side.CLIENT, "Read the client's copy of the world");
            this.runSide.onChange(this.session::setSide);
            this.liveButton.setToolTipText(Tooltip.of("Read Live")
                    .text("Reads again after each read while this tab is visible").html());
            this.liveButton.getAccessibleContext().setAccessibleName("Read Live");
            this.liveButton.addActionListener(event -> this.session.setLive(this.liveButton.isSelected(), this.liveIntervalMs));
            FlatIconButton.configure(this.liveInterval);
            this.liveInterval.setToolTipText("Time between live reads");
            this.liveInterval.addActionListener(event ->
                    intervalMenu().show(this.liveInterval, 0, this.liveInterval.getHeight()));
            this.liveInterval.setText(seconds(this.liveIntervalMs));
            this.refresh.setToolTipText(Tooltip.of("Read Again").html());
            this.refresh.getAccessibleContext().setAccessibleName("Read Again");
            this.refresh.addActionListener(event -> this.session.refresh());
            this.toolsButton.setToolTipText(Tooltip.of("Tools").text("Project scripts that read this subject").html());
            this.toolsButton.getAccessibleContext().setAccessibleName("Tools");
            this.toolsButton.addActionListener(event -> ToolsMenu.menu(this.session, SubjectPanel.this,
                    SubjectPanel.this.services.navigator()).show(this.toolsButton, 0, this.toolsButton.getHeight()));
            List<JComponent> controls = new ArrayList<>(List.of(this.runSide, this.liveButton, this.liveInterval, this.refresh));
            if (this.session.readsTools()) controls.add(this.toolsButton);
            controls.forEach(SubjectPanel.this.header::addControl);
        }

        private JPopupMenu intervalMenu() {
            JPopupMenu menu = new JPopupMenu();
            ButtonGroup group = new ButtonGroup();
            for (int interval : InspectionSession.LIVE_INTERVALS_MS) {
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(seconds(interval), interval == this.liveIntervalMs);
                item.addActionListener(event -> {
                    this.liveIntervalMs = interval;
                    this.liveInterval.setText(seconds(interval));
                    this.session.setLive(this.liveButton.isSelected(), interval);
                });
                group.add(item);
                menu.add(item);
            }
            return menu;
        }

        /** The overview beside the Data and Java object tabs, or a read's problem when it reported nothing. */
        private JComponent content(JComponent overview) {
            this.views.addTab("Overview", overview);
            this.views.addTab("Data", this.data);
            this.views.addTab("Java object", new JScrollPane(this.object));
            this.problem.setEditable(false);
            this.problem.setBorder(UiMetrics.messagePadding());
            this.cards.add(this.views, RESULT_CARD);
            this.cards.add(new JScrollPane(this.problem), PROBLEM_CARD);
            return this.cards;
        }

        /** Messages shown only while they apply: a replaced subject, and a read that failed. */
        private JComponent notices() {
            JPanel notices = new JPanel();
            notices.setLayout(new BoxLayout(notices, BoxLayout.Y_AXIS));
            for (JLabel notice : List.of(this.replaced, this.problemNotice)) {
                notice.setBorder(new CompoundBorder(
                        DynamicMatteBorder.separatorRule(0, 0, 1, 0),
                        UiMetrics.pagePadding(6, 6)
                ));
                notice.setAlignmentX(Component.LEFT_ALIGNMENT);
                notice.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
                notice.setVisible(false);
                notices.add(notice);
            }
            this.replaced.setIcon(Icons.WARNING);
            this.problemNotice.setIcon(Icons.ERROR);
            return notices;
        }

        /** Where the subject is: a block's position and dimension, an entity's UUID, or a stack's slot. */
        private List<JComponent> where() {
            return switch (SubjectRef.parseOccurrence(this.subject.subject())) {
                case SubjectRef.Block block -> List.of(
                        SubjectHeader.text(block.x() + ", " + block.y() + ", " + block.z()),
                        SubjectHeader.text(block.dimension()));
                case SubjectRef.Entity entity -> List.of(SubjectHeader.text(entity.uuid().toString()));
                case SubjectRef.Stack stack -> List.of(SubjectHeader.text(stack.menu() == SubjectRef.Stack.INVENTORY
                        ? "Inventory slot " + stack.slot() : "Container slot " + stack.slot()));
            };
        }

        /** The classes the subject was read with, linked to their source. */
        private List<Fact> classes() {
            List<Fact> classes = new ArrayList<>();
            for (SubjectIdentity.ClassLink link : this.identity.classes()) {
                classes.add(Fact.text(link.label(), simpleName(link.binaryName())).withLink(FactLink.toClass(link.binaryName())));
            }
            return classes;
        }

        /** The read's sections and the tools' sections. */
        private List<FactsPanel.Part> parts() {
            List<FactsPanel.Part> parts = new ArrayList<>(FactsPanel.parts(this.state.read().sections()));
            parts.addAll(ToolsMenu.parts(this.state.tools(), this.state.toolsProblem()));
            return parts;
        }

        /** Whether the header shows the stack that was selected in the game, rather than a replacement's item. */
        private boolean showsSelectedItem() {
            return this.identity.iconItem().equals(this.subject.identity().iconItem());
        }

        /** Shows the session's state: identity, the read, the tools and their data. */
        private void show(InspectionSession.State next) {
            if (SubjectPanel.this.disposed) return;
            this.state = next;
            this.refresh.setEnabled(!next.reading() || next.live());
            if (next.replaced() != null) {
                this.replaced.setText("Replaced: previously " + next.replaced().title() + " (" + next.replaced().registryId() + ")");
                this.replaced.setVisible(true);
            }
            InspectionSession.Read read = next.read();
            if (!next.identity().equals(this.identity)) {
                this.identity = next.identity();
                showDefinition(definition(this.identity));
            } else if (read.hasFacts() || read.outcome() == InspectionSession.Outcome.NONE) {
                showSections();
            }
            if (read.hasFacts() || read.outcome() == InspectionSession.Outcome.NONE) {
                showObject(read.value());
                ((CardLayout) this.cards.getLayout()).show(this.cards, RESULT_CARD);
            } else {
                this.problem.setText(read.problem());
                this.problem.setCaretPosition(0);
                ((CardLayout) this.cards.getLayout()).show(this.cards, PROBLEM_CARD);
            }
            boolean notice = read.outcome() == InspectionSession.Outcome.PARTIAL
                    || read.outcome() == InspectionSession.Outcome.OUTDATED;
            if (notice) showProblemNotice(read.problem(), read.details());
            else this.problemNotice.setVisible(false);
            this.data.show(dataRoots(next));
        }

        private void showObject(ExecutionValue value) {
            if (value == null || value == this.shownValue) return;
            if (this.shownValue != null) {
                this.object.replaceResult(value);
            } else {
                this.object.showResult(value);
                this.object.expandRow(0);
            }
            this.shownValue = value;
        }

        private void openData(String name) {
            this.views.setSelectedComponent(this.data);
            this.data.reveal(name);
        }

        private void showProblemNotice(String text, String details) {
            this.problemNotice.setText(text);
            ThemeColors.keepForeground(this.problemNotice, ThemeColors::text);
            this.problemNotice.setToolTipText(Tooltip.of("").code(details).html());
            this.problemNotice.setVisible(true);
        }

        private void dispose() {
            this.session.dispose();
            this.data.dispose();
        }
    }

    /**
     * The data facts of the built-in read and of every tool, named like the sections showing them: a tool's data
     * after the tool's name.
     */
    private static List<DataRows.Root> dataRoots(InspectionSession.State state) {
        List<DataRows.Root> roots = new ArrayList<>(dataRoots("", state.read().sections()));
        for (InspectionSession.ToolRead tool : state.tools()) roots.addAll(dataRoots(tool.tool().name(), tool.sections()));
        return roots;
    }

    private static List<DataRows.Root> dataRoots(String source, List<FactSection> sections) {
        List<DataRows.Root> roots = new ArrayList<>();
        for (FactSection section : sections) {
            for (Fact fact : section.facts()) {
                if (fact.kind() != Fact.Kind.DATA) continue;
                String name = section.title() + " › " + fact.label();
                roots.add(new DataRows.Root(source.isEmpty() ? name : source + " › " + name, fact.data()));
            }
        }
        return roots;
    }
}
