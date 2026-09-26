package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.RegistryIds;
import com.github.minecraft_ta.totalDebugCompanion.inspection.InspectionSession;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.SubjectLinks;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService.Side;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.SegmentedToggle;
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
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shows a block or entity selected in the game as its {@link InspectionSession} reads it. The overview starts with
 * what occupies the subject, followed by the readers' and the tools' sections; the returned object remains available
 * for code-level inspection. The header, the tab and the tools follow what occupies the subject, and a replaced block
 * is called out. A read that fails keeps the facts it did report, and a failed read keeps the previous one on screen,
 * marked outdated.
 */
public final class InspectionPanel extends JPanel {
    private static final String RESULT_CARD = "result";
    private static final String PROBLEM_CARD = "problem";

    private final InspectSubjectPayload subject;
    private final Consumer<NavigationTarget> navigator;
    private final InspectionSession session;
    private final ItemIconService icons;
    private final Runnable removeIconListener;
    private final SubjectHeader header = new SubjectHeader();
    private final JLabel replaced = new JLabel();
    private final JLabel problemNotice = new JLabel();
    private final ItemTabIcon tabIcon = new ItemTabIcon(Icons.EVALUATE_EXPRESSION);
    private final SegmentedToggle<Side> runSide = new SegmentedToggle<>(List.of(Side.SERVER, Side.CLIENT),
            side -> side == Side.CLIENT ? "Client" : "Server");
    private final JButton live = new FlatIconButton(Icons.RUN, true);
    private final JButton liveInterval = new JButton();
    private final JButton refresh = new FlatIconButton(Icons.REFRESH, false);
    private final JButton toolsButton = new FlatIconButton(Icons.SCRIPT_FILE, false);
    private final JPanel overview = new JPanel(new BorderLayout());
    private final JTabbedPane views = new JTabbedPane();
    private final ScriptResultTree object = new ScriptResultTree();
    private final DataView data = new DataView();
    private final Set<String> collapsed = new HashSet<>();
    private final JTextArea problem = new JTextArea();
    private final JPanel cards = new JPanel(new CardLayout());
    private final FactsPanel.Actions actions = new FactsPanel.Actions() {
        @Override
        public void open(FactLink link) {
            try {
                InspectionPanel.this.navigator.accept(SubjectLinks.target(link));
            } catch (IllegalArgumentException unsupported) {
                showProblemNotice(unsupported.getMessage(), unsupported.getMessage());
            }
        }

        @Override
        public void openData(String name) {
            InspectionPanel.this.views.setSelectedComponent(InspectionPanel.this.data);
            InspectionPanel.this.data.reveal(name);
        }

        @Override
        public void openScript(Path script) {
            InspectionPanel.this.navigator.accept(new NavigationTarget.LocalFile(script));
        }
    };
    private FactsPanel facts;
    private SubjectIdentity identity;
    private ExecutionValue shownValue;
    private int liveIntervalMs = 1_000;
    private boolean disposed;

    public InspectionPanel(
            InspectSubjectPayload subject,
            Supplier<SnippetExecutionService> snippets,
            Supplier<ScriptFiles> scripts,
            ItemIconService icons,
            Consumer<NavigationTarget> navigator
    ) {
        super(new BorderLayout());
        this.subject = Objects.requireNonNull(subject, "subject");
        this.identity = subject.identity();
        this.icons = Objects.requireNonNull(icons, "icons");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.session = new InspectionSession(subject, snippets, scripts, this::show, this::isShowing);

        JPanel top = new JPanel(new BorderLayout());
        top.add(header(), BorderLayout.NORTH);
        top.add(notices(), BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        this.views.addTab("Overview", scroll(this.overview));
        this.views.addTab("Data", this.data);
        this.views.addTab("Java object", new JScrollPane(this.object));
        this.problem.setEditable(false);
        this.problem.setBorder(UiMetrics.messagePadding());
        this.cards.add(this.views, RESULT_CARD);
        this.cards.add(new JScrollPane(this.problem), PROBLEM_CARD);
        add(this.cards, BorderLayout.CENTER);
        showSections(List.of(), List.of());
        this.removeIconListener = icons.addListener(this::reloadIcons);
        reloadIcons();
    }

    /** The icon, name and where the subject is, with the controls on the same row. */
    private JComponent header() {
        this.runSide.setToolTipText(Side.SERVER, "Read the server's copy of the world");
        this.runSide.setToolTipText(Side.CLIENT, "Read the client's copy of the world");
        this.runSide.onChange(this.session::setSide);
        this.live.setToolTipText(Tooltip.of("Read Live")
                .text("Reads again after each read while this tab is visible").html());
        this.live.getAccessibleContext().setAccessibleName("Read Live");
        this.live.addActionListener(event -> this.session.setLive(this.live.isSelected(), this.liveIntervalMs));
        FlatIconButton.configure(this.liveInterval);
        this.liveInterval.setToolTipText("Time between live reads");
        this.liveInterval.addActionListener(event ->
                intervalMenu().show(this.liveInterval, 0, this.liveInterval.getHeight()));
        this.liveInterval.setText(seconds(this.liveIntervalMs));
        this.refresh.setToolTipText(Tooltip.of("Read Again").html());
        this.refresh.getAccessibleContext().setAccessibleName("Read Again");
        this.refresh.addActionListener(event -> refresh());
        this.toolsButton.setToolTipText(Tooltip.of("Tools").text("Project scripts that read this subject").html());
        this.toolsButton.getAccessibleContext().setAccessibleName("Tools");
        this.toolsButton.addActionListener(event -> ToolsMenu.menu(this.session, this, this.navigator)
                .show(this.toolsButton, 0, this.toolsButton.getHeight()));
        for (JComponent control : List.of(this.runSide, this.live, this.liveInterval, this.refresh, this.toolsButton)) {
            this.header.addControl(control);
        }
        showIdentityHeader();
        return this.header;
    }

    private JPopupMenu intervalMenu() {
        JPopupMenu menu = new JPopupMenu();
        ButtonGroup group = new ButtonGroup();
        for (int interval : InspectionSession.LIVE_INTERVALS_MS) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(seconds(interval), interval == this.liveIntervalMs);
            item.addActionListener(event -> {
                this.liveIntervalMs = interval;
                this.liveInterval.setText(seconds(interval));
                this.session.setLive(this.live.isSelected(), interval);
            });
            group.add(item);
            menu.add(item);
        }
        return menu;
    }

    static String seconds(int millis) {
        return (millis % 1_000 == 0 ? Integer.toString(millis / 1_000) : Double.toString(millis / 1_000.0)) + " s";
    }

    /** Where the subject is, and the mod it belongs to. */
    private void showIdentityHeader() {
        this.header.setTitle(this.identity.title());
        List<JComponent> parts = new ArrayList<>();
        switch (SubjectRef.parseWorld(this.subject.subject())) {
            case SubjectRef.Block block -> {
                parts.add(SubjectHeader.text(block.x() + ", " + block.y() + ", " + block.z()));
                parts.add(SubjectHeader.text(block.dimension()));
            }
            case SubjectRef.Entity entity -> parts.add(SubjectHeader.text(entity.uuid().toString()));
        }
        String namespace = namespace(this.identity.registryId());
        if (!namespace.isEmpty()) {
            String modName = this.identity.modName().isBlank() ? namespace : this.identity.modName();
            parts.add(new LinkLabel(modName, Icons.MOD, "mod " + namespace,
                    () -> this.navigator.accept(new NavigationTarget.ModPage(namespace))));
        }
        this.header.setSubtitle(parts);
    }

    static SubjectRef.Definition definition(SubjectIdentity identity) {
        return new SubjectRef.Definition(identity.kind() == SubjectIdentity.Kind.ENTITY
                ? RegistryIds.ENTITY_TYPE : RegistryIds.BLOCK, identity.registryId());
    }

    private static String namespace(String registryId) {
        int separator = registryId.indexOf(':');
        return separator <= 0 ? "" : registryId.substring(0, separator);
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

    /** The first overview section: what occupies the subject and the classes behind it. */
    static FactSection identitySection(SubjectIdentity identity) {
        List<Fact> facts = new ArrayList<>();
        facts.add(Fact.text("ID", identity.registryId()).withLink(FactLink.toSubject(definition(identity))));
        for (SubjectIdentity.ClassLink link : identity.classes()) {
            facts.add(Fact.text(link.label(), simpleName(link.binaryName()))
                    .withLink(FactLink.toClass(link.binaryName())));
        }
        String title = identity.kind() == SubjectIdentity.Kind.ENTITY ? "Entity" : "Block";
        return new FactSection(title, facts, facts.size());
    }

    /** The name of what currently occupies the subject, for the editor tab. */
    public String title() {
        return this.identity.title();
    }

    /** Starts a new read of the subject, replacing one still running. */
    public void refresh() {
        this.session.refresh();
    }

    /** The reading behind the page; its state is what the page shows. */
    public InspectionSession session() {
        return this.session;
    }

    /** Shows the session's state: identity, the built-in read, the tools and their data. */
    private void show(InspectionSession.State state) {
        if (this.disposed) return;
        this.refresh.setEnabled(!state.reading() || state.live());
        showIdentity(state);
        InspectionSession.Read read = state.read();
        if (read.hasFacts() || read.outcome() == InspectionSession.Outcome.NONE) {
            showSections(read.sections(), ToolsMenu.parts(state.tools(), state.toolsProblem()));
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
        showData(state);
    }

    /** Follows what occupies the subject: header, sections, icons and the tab title. */
    private void showIdentity(InspectionSession.State state) {
        if (state.replaced() != null) {
            this.replaced.setText("Replaced: previously " + state.replaced().title() + " (" + state.replaced().registryId() + ")");
            this.replaced.setVisible(true);
        }
        if (state.identity().equals(this.identity)) return;
        this.identity = state.identity();
        showIdentityHeader();
        reloadIcons();
        EditorTabs tabs = (EditorTabs) SwingUtilities.getAncestorOfClass(EditorTabs.class, this);
        if (tabs != null) tabs.refreshEditorTitles();
    }

    /** Shows the identity section, the read's sections and the tools' sections, updating in place where possible. */
    private void showSections(List<FactSection> read, List<FactsPanel.Part> tools) {
        List<FactsPanel.Part> parts = new ArrayList<>();
        parts.add(new FactsPanel.Part(identitySection(this.identity), null));
        parts.addAll(FactsPanel.parts(read));
        parts.addAll(tools);
        if (this.facts == null || !this.facts.update(parts)) {
            this.facts = new FactsPanel(parts, this.icons, this.actions, this.collapsed);
            this.overview.removeAll();
            this.overview.add(this.facts, BorderLayout.NORTH);
            this.overview.revalidate();
            this.overview.repaint();
        }
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

    /**
     * The data facts of the built-in read and of every tool, named like the sections showing them: a tool's data
     * after the tool's name.
     */
    private void showData(InspectionSession.State state) {
        List<DataRows.Root> roots = new ArrayList<>(dataRoots("", state.read().sections()));
        for (InspectionSession.ToolRead tool : state.tools()) roots.addAll(dataRoots(tool.tool().name(), tool.sections()));
        this.data.show(roots);
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

    private void showProblemNotice(String text, String details) {
        this.problemNotice.setText(text);
        ThemeColors.keepForeground(this.problemNotice, ThemeColors::text);
        this.problemNotice.setToolTipText(Tooltip.of("").code(details).html());
        this.problemNotice.setVisible(true);
    }

    private void reloadIcons() {
        if (this.disposed) return;
        if (this.facts != null) {
            this.facts.reloadIcons();
        }
        // The client's icon has the selected stack's tints; after a replacement the captured default stack's are used.
        boolean selectedItem = this.identity.iconItem().equals(this.subject.identity().iconItem());
        CatalogIndex.ItemIcon replacement = selectedItem || this.identity.iconItem().isEmpty()
                ? null : this.icons.itemIcon(this.identity.iconItem());
        String model = selectedItem ? this.subject.iconModel() : replacement == null ? "" : replacement.model();
        Map<Integer, Integer> tints = selectedItem ? this.subject.iconTints()
                : replacement == null ? Map.of() : replacement.tints();
        Icon plate = new PlateIcon(ContentKinds.of(definition(this.identity).registry()).icon(), SubjectHeader.ICON_SIZE);
        this.icons.render(model, tints, SubjectHeader.ICON_SIZE)
                .thenAccept(image -> SwingUtilities.invokeLater(() -> {
                    if (!this.disposed) this.header.setIcon(image.<Icon>map(ImageIcon::new).orElse(plate));
                }));
        this.icons.render(model, tints, this.tabIcon.size())
                .thenAccept(image -> SwingUtilities.invokeLater(() -> {
                    if (this.disposed) return;
                    this.tabIcon.setImage(image.orElse(null));
                    Component tabs = SwingUtilities.getAncestorOfClass(JTabbedPane.class, this);
                    if (tabs != null) tabs.repaint();
                }));
    }

    /** The subject's rendered item for its editor tab, with a generic icon until the item can be drawn. */
    public ItemTabIcon tabIcon() {
        return this.tabIcon;
    }

    public void dispose() {
        requireEdt();
        this.disposed = true;
        this.session.dispose();
        this.removeIconListener.run();
        this.data.dispose();
    }

    private static JScrollPane scroll(JComponent content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private static String simpleName(String binaryName) {
        return binaryName.substring(binaryName.lastIndexOf('.') + 1);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Inspection panels are used on the EDT");
        }
    }
}
