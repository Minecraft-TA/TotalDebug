package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.SubjectLinks;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionTextDisplay;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptCompilationService;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptSubject;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService.Side;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.LinkLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectIcons;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectHeader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.values.ScriptResultTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Shows a block or entity selected in the game. Each refresh runs the built-in readers against {@code target()} on
 * the chosen side through the ordinary snippet path, so it sees exactly what a script bound to the same subject sees.
 * The overview starts with what occupies the subject, followed by the readers' and tools' sections; the returned
 * object remains available for code-level inspection. Every read reports what currently occupies the subject: the
 * header, the tab and the tools follow it, and a replaced block is called out. A read that fails keeps the facts it
 * did report, and a failed refresh keeps the previous read on screen, marked as outdated.
 */
public final class InspectionPanel extends JPanel {
    static final List<String> FACES = List.of("", "DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST");
    static final List<Integer> LIVE_INTERVALS_MS = List.of(500, 1_000, 2_000, 5_000);
    private static final String RESULT_CARD = "result";
    private static final String PROBLEM_CARD = "problem";

    private final InspectSubjectPayload subject;
    private final Consumer<NavigationTarget> navigator;
    private final Supplier<SnippetExecutionService> snippets;
    private final ItemIconService icons;
    private final Runnable removeIconListener;
    private final SubjectHeader header = new SubjectHeader();
    private final JLabel replaced = new JLabel();
    private final JLabel problemNotice = new JLabel();
    private final ItemTabIcon tabIcon = new ItemTabIcon(Icons.EVALUATE_EXPRESSION);
    private final JComboBox<Side> runSide = new JComboBox<>(new Side[]{Side.SERVER, Side.CLIENT});
    private final JComboBox<String> face = new JComboBox<>(FACES.toArray(String[]::new));
    private final JButton refresh = new JButton(Icons.REFRESH);
    private final JButton toolsButton = new JButton("Tools");
    private final JToggleButton live = new JToggleButton("Live");
    private final JComboBox<Integer> liveInterval = new JComboBox<>(LIVE_INTERVALS_MS.toArray(Integer[]::new));
    private final Timer liveTimer = new Timer(1_000, event -> liveTick());
    private final ToolsPanel tools;
    private final JPanel builtIn = new JPanel(new BorderLayout());
    private final JPanel overview = new JPanel(new BorderLayout());
    private final JTabbedPane views = new JTabbedPane();
    private final ScriptResultTree object = new ScriptResultTree();
    private final DataView data = new DataView();
    private final Map<String, List<DataRows.Root>> dataBySource = new LinkedHashMap<>();
    private CompletableFuture<Void> toolRun = CompletableFuture.completedFuture(null);
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
        public void openData(String section, String label) {
            InspectionPanel.this.views.setSelectedComponent(InspectionPanel.this.data);
            InspectionPanel.this.data.reveal(section + " › " + label);
        }
    };
    private FactsPanel facts;
    private List<FactSection> readSections = List.of();
    private SubjectIdentity identity;
    private boolean hasRead;
    private boolean objectShown;
    private SnippetExecutionService.Execution active;
    private long revision;
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
        this.snippets = Objects.requireNonNull(snippets, "snippets");
        this.icons = Objects.requireNonNull(icons, "icons");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.tools = new ToolsPanel(subject, () -> this.identity, snippets, Objects.requireNonNull(scripts, "scripts"),
                icons, navigator, this.actions, this::refresh, this::showToolData);
        JPanel sections = new JPanel();
        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
        this.builtIn.setAlignmentX(Component.LEFT_ALIGNMENT);
        this.tools.setAlignmentX(Component.LEFT_ALIGNMENT);
        this.tools.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        sections.add(this.builtIn);
        sections.add(this.tools);
        this.overview.add(sections, BorderLayout.NORTH);

        JPanel top = new JPanel(new BorderLayout());
        top.add(header(), BorderLayout.NORTH);
        top.add(notices(), BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        this.views.addTab("Overview", scroll(this.overview));
        this.views.addTab("Data", this.data);
        this.views.addTab("Object", new JScrollPane(this.object));
        this.problem.setEditable(false);
        this.problem.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        this.cards.add(this.views, RESULT_CARD);
        this.cards.add(new JScrollPane(this.problem), PROBLEM_CARD);
        add(this.cards, BorderLayout.CENTER);

        this.runSide.setRenderer(labels(value -> value == Side.CLIENT ? "Client" : "Server"));
        this.runSide.setToolTipText("Read the server's or the client's copy of the world");
        this.face.setRenderer(labels(value -> sideLabel(value == null ? "" : value.toString())));
        this.face.setToolTipText(Tooltip.of("Side passed to capability queries")
                .text("None asks for the unsided handler, which the mod defines; it is not a combination of the six faces.")
                .html());
        this.runSide.addActionListener(event -> refresh());
        this.face.addActionListener(event -> refresh());
        this.refresh.setToolTipText("Read again");
        this.refresh.addActionListener(event -> refresh());
        this.liveInterval.setSelectedItem(1_000);
        this.liveInterval.setVisible(false);
        this.liveInterval.setRenderer(labels(value -> value instanceof Integer millis
                ? (millis % 1_000 == 0 ? millis / 1_000 + " s" : millis / 1_000.0 + " s") : ""));
        this.liveInterval.setToolTipText("Time between reads while Live is on");
        this.live.setToolTipText("Read again automatically while this tab is visible");
        this.live.addActionListener(event -> {
            this.liveInterval.setVisible(this.live.isSelected());
            if (this.live.isSelected()) refresh();
            else this.liveTimer.stop();
        });
        this.liveTimer.setRepeats(false);
        this.toolsButton.setToolTipText("Project scripts run on this subject");
        this.toolsButton.addActionListener(event ->
                this.tools.menu().show(this.toolsButton, 0, this.toolsButton.getHeight()));
        showSections();
        this.removeIconListener = icons.addListener(this::reloadIcons);
        reloadIcons();
    }

    /** The icon, name and links to what the subject is, with the controls on the same row. */
    private JComponent header() {
        for (JComponent control : new JComponent[]{this.runSide, this.face, this.live, this.liveInterval,
                this.toolsButton, this.refresh}) {
            this.header.addControl(control);
        }
        showIdentityHeader();
        return this.header;
    }

    /** The registry id, the owning mod and the definition this occurrence is an instance of. */
    private void showIdentityHeader() {
        this.header.setTitle(this.identity.title());
        List<JComponent> parts = new ArrayList<>();
        parts.add(SubjectHeader.text(this.identity.registryId()));
        String namespace = namespace(this.identity.registryId());
        if (!namespace.isEmpty()) {
            String modName = this.identity.modName().isBlank() ? namespace : this.identity.modName();
            parts.add(new LinkLabel(modName, Icons.MOD, "mod " + namespace,
                    () -> this.navigator.accept(new NavigationTarget.ModPage(namespace))));
            SubjectRef.Definition definition = definition(this.identity);
            parts.add(new LinkLabel(this.identity.title(), SubjectIcons.definition(definition.kind()),
                    definition.format(), () -> this.navigator.accept(new NavigationTarget.Definition(definition))));
        }
        this.header.setSubtitle(parts);
    }

    static SubjectRef.Definition definition(SubjectIdentity identity) {
        return new SubjectRef.Definition(identity.kind() == SubjectIdentity.Kind.ENTITY
                ? SubjectRef.DefinitionKind.ENTITY_TYPE : SubjectRef.DefinitionKind.BLOCK, identity.registryId());
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
                    BorderFactory.createEmptyBorder(6, 12, 6, 12)
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

    /** The first overview section: what occupies the subject, where it is and the classes behind it. */
    static FactSection identitySection(SubjectIdentity identity, SubjectRef.InWorld subject) {
        List<Fact> facts = new ArrayList<>();
        facts.add(Fact.text("ID", identity.registryId()).withLink(FactLink.toSubject(definition(identity))));
        if (!identity.modName().isBlank()) {
            Fact mod = Fact.text("Mod", identity.modName());
            try {
                mod = mod.withLink(FactLink.toSubject(new SubjectRef.Mod(namespace(identity.registryId()))));
            } catch (IllegalArgumentException notAModId) {
                // A namespace that is not a mod id has no mod page to link to.
            }
            facts.add(mod);
        }
        switch (subject) {
            case SubjectRef.Block block -> facts.add(Fact.text("Position",
                    block.x() + ", " + block.y() + ", " + block.z() + " in " + block.dimension()));
            case SubjectRef.Entity entity -> facts.add(Fact.text("UUID", entity.uuid().toString()));
        }
        for (SubjectIdentity.ClassLink link : identity.classes()) {
            facts.add(Fact.text(link.label(), simpleName(link.binaryName()))
                    .withLink(FactLink.toClass(link.binaryName())));
        }
        String title = identity.kind() == SubjectIdentity.Kind.ENTITY ? "Entity" : "Block";
        return new FactSection(title, facts, facts.size());
    }

    /** The side selector's text for a face name; the empty name is the unsided query. */
    static String sideLabel(String faceName) {
        return "Side: " + (faceName.isEmpty() ? "None" : capitalized(faceName));
    }

    /** The name of what currently occupies the subject, for the editor tab. */
    public String title() {
        return this.identity.title();
    }

    /** The snippet each refresh runs: the built-in readers report sections, and the target is the result. */
    static String readerSource(String faceName) {
        String side = faceName.isEmpty() ? "null" : "Direction." + faceName;
        return """
                import com.github.minecraft_ta.totaldebug.inspection.InspectionReaders;
                import net.minecraft.core.Direction;
                InspectionReaders.read(target(), %s, facts());
                return target();
                """.formatted(side);
    }

    /** Starts a new read of the subject, replacing one still running. */
    public void refresh() {
        requireEdt();
        if (this.disposed) return;
        this.liveTimer.stop();
        cancelActive();
        Side selectedSide = (Side) this.runSide.getSelectedItem();
        JavaSnippetSource.GeneratedSource source = JavaSnippetSource.body("InspectTarget",
                readerSource((String) this.face.getSelectedItem()));
        long current = ++this.revision;
        if (!awaitReadiness(current, selectedSide)) return;
        try {
            this.active = this.snippets.get().execute(
                    source,
                    selectedSide,
                    ScriptExecutionEnvironment.POST_TICK,
                    new ScriptSubject(SubjectRef.parseWorld(this.subject.subject()), this.subject.gameSessionId())
            );
        } catch (RuntimeException exception) {
            readFailed(exception.getMessage());
            scheduleLive(current);
            return;
        }
        if (!this.live.isSelected()) {
            this.refresh.setEnabled(false);
        }
        this.toolRun = this.tools.run(selectedSide);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        this.active.completion().whenComplete((outcome, failure) -> SwingUtilities.invokeLater(() -> {
            try {
                finish(current, selectedSide, source, outcome, failure);
            } finally {
                finished.complete(null);
            }
        }));
        // A replaced subject starts its tools again while finishing; the next read waits for the run current then.
        finished.thenCompose(ignored -> this.toolRun).whenComplete((ignored, failure) ->
                SwingUtilities.invokeLater(() -> scheduleLive(current)));
    }

    /**
     * A tab opened from the game usually exists before the runtime index and the server comparison are ready. Until
     * the chosen side can run the read, the page shows why and reads as soon as that changes. Returns whether the
     * read can start now.
     */
    private boolean awaitReadiness(long current, Side side) {
        ScriptCompilationService.Readiness readiness;
        try {
            readiness = this.snippets.get().readiness(side);
        } catch (RuntimeException unavailable) {
            return true;
        }
        if (readiness.ready()) return true;
        readFailed(readiness.detail());
        readiness.changed().thenRunAsync(() -> {
            if (!this.disposed && current == this.revision) refresh();
        }, SwingUtilities::invokeLater);
        return false;
    }

    /** Queues the next live read once the current one, including tools, has finished. */
    private void scheduleLive(long current) {
        if (this.disposed || current != this.revision || !this.live.isSelected()) return;
        this.liveTimer.setInitialDelay((Integer) this.liveInterval.getSelectedItem());
        this.liveTimer.restart();
    }

    private void liveTick() {
        if (this.disposed || !this.live.isSelected()) return;
        if (!isShowing()) {
            // Hidden tabs keep their last read; check again later instead of loading the game.
            this.liveTimer.restart();
            return;
        }
        refresh();
    }

    private void finish(
            long current,
            Side selectedSide,
            JavaSnippetSource.GeneratedSource source,
            ExecutionResult outcome,
            Throwable failure
    ) {
        if (this.disposed || current != this.revision) return;
        this.active = null;
        present(selectedSide, outcome, failure, source::mapDiagnostics);
    }

    /**
     * Presents a finished read: its identity, then its facts even when the read failed part way. A read without facts
     * leaves the previous one on screen. {@code diagnostics} maps error positions to the snippet's source.
     */
    void present(Side selectedSide, ExecutionResult outcome, Throwable failure, UnaryOperator<String> diagnostics) {
        this.refresh.setEnabled(true);
        if (failure != null) {
            readFailed(failure.getMessage());
            return;
        }
        if (outcome.identity() != null) {
            applyIdentity(outcome.identity(), selectedSide);
        }
        boolean completed = outcome.status() == ExecutionStatus.RUN_COMPLETED && outcome.value() != null;
        String error = ExecutionTextDisplay.format(outcome.error());
        error = completed ? "" : error.isBlank() ? "The read ended without a result" : diagnostics.apply(error);
        if (!completed && outcome.facts().isEmpty()) {
            readFailed(error);
            return;
        }
        showOutcome(outcome);
        String logs = ExecutionTextDisplay.format(outcome.logs()).strip();
        if (!completed) {
            showProblemNotice("The read stopped early: " + firstLine(error), error + "\n\n" + logs);
        } else if (hasProblems(outcome)) {
            showProblemNotice("Some parts could not be read", logs);
        } else {
            this.problemNotice.setVisible(false);
        }
    }

    /**
     * Takes the identity a read reported. A different registry id means the subject was replaced: a notice says
     * what it was, and the tools are selected again for what is there now.
     */
    void applyIdentity(SubjectIdentity reported, Side selectedSide) {
        if (reported.equals(this.identity)) return;
        boolean replacedNow = !reported.registryId().equals(this.identity.registryId());
        if (replacedNow) {
            this.replaced.setText("Replaced: previously " + this.identity.title() + " ("
                    + this.identity.registryId() + ")");
            this.replaced.setVisible(true);
        }
        this.identity = reported;
        showIdentityHeader();
        showSections();
        reloadIcons();
        EditorTabs tabs = (EditorTabs) SwingUtilities.getAncestorOfClass(EditorTabs.class, this);
        if (tabs != null) tabs.refreshEditorTitles();
        if (replacedNow) {
            this.toolRun = this.tools.run(selectedSide);
        }
    }

    /** Presents a completed read's sections and returned object. */
    void showOutcome(ExecutionResult outcome) {
        showData("", outcome.facts());
        this.readSections = outcome.facts();
        showSections();
        if (outcome.value() != null) {
            if (this.objectShown) {
                this.object.replaceResult(outcome.value());
            } else {
                this.object.showResult(outcome.value());
                this.object.expandRow(0);
                this.objectShown = true;
            }
        }
        ((CardLayout) this.cards.getLayout()).show(this.cards, RESULT_CARD);
        this.hasRead = true;
    }

    /** Shows the identity section followed by the latest read's sections, updating in place where possible. */
    private void showSections() {
        List<FactSection> sections = new ArrayList<>();
        sections.add(identitySection(this.identity, SubjectRef.parseWorld(this.subject.subject())));
        sections.addAll(this.readSections);
        if (this.facts == null || !this.facts.update(sections)) {
            this.facts = new FactsPanel(sections, this.icons, this.actions, this.collapsed);
            this.builtIn.removeAll();
            this.builtIn.add(this.facts, BorderLayout.CENTER);
            this.overview.revalidate();
            this.overview.repaint();
        }
    }

    /**
     * Reports a read that produced nothing to show. The previous read stays on screen with a notice that it is
     * outdated; without one the problem replaces the overview.
     */
    private void readFailed(String message) {
        String text = message == null || message.isBlank() ? "The read failed" : message;
        this.refresh.setEnabled(true);
        if (this.hasRead) {
            showProblemNotice("Showing the previous read. This one failed: " + firstLine(text), text);
            return;
        }
        this.problem.setText(text);
        this.problem.setCaretPosition(0);
        ((CardLayout) this.cards.getLayout()).show(this.cards, PROBLEM_CARD);
        this.problemNotice.setVisible(false);
    }

    private void showProblemNotice(String text, String details) {
        this.problemNotice.setText(text);
        ThemeColors.keepForeground(this.problemNotice, ThemeColors::text);
        this.problemNotice.setToolTipText(Tooltip.of("").code(details).html());
        this.problemNotice.setVisible(true);
    }

    private static boolean hasProblems(ExecutionResult outcome) {
        return outcome.facts().stream().flatMap(section -> section.facts().stream())
                .anyMatch(fact -> fact.kind() == Fact.Kind.PROBLEM);
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse(text);
    }


    private void reloadIcons() {
        if (this.disposed) return;
        if (this.facts != null) {
            this.facts.reloadIcons();
        }
        this.tools.reloadIcons();
        // The client's icon has the selected stack's tints; after a replacement the captured default stack's are used.
        boolean selectedItem = this.identity.iconItem().equals(this.subject.identity().iconItem());
        CatalogIndex.ItemIcon replacement = selectedItem || this.identity.iconItem().isEmpty()
                ? null : this.icons.itemIcon(this.identity.iconItem());
        String model = selectedItem ? this.subject.iconModel() : replacement == null ? "" : replacement.model();
        Map<Integer, Integer> tints = selectedItem ? this.subject.iconTints()
                : replacement == null ? Map.of() : replacement.tints();
        this.icons.render(model, tints, SubjectHeader.ICON_SIZE)
                .thenAccept(image -> SwingUtilities.invokeLater(() -> {
                    if (!this.disposed) this.header.setIcon(image.map(ImageIcon::new).orElse(null));
                }));
        this.icons.render(model, tints, this.tabIcon.size())
                .thenAccept(image -> SwingUtilities.invokeLater(() -> {
                    if (this.disposed) return;
                    this.tabIcon.setImage(image.orElse(null));
                    Component tabs = SwingUtilities.getAncestorOfClass(JTabbedPane.class, this);
                    if (tabs != null) tabs.repaint();
                }));
    }

    /** Shows the data facts a tool reported in the Data view, after the built-in readers' data. */
    private void showToolData(String tool, List<FactSection> sections) {
        showData(tool, sections);
    }

    /**
     * Replaces the data reported by one source, the built-in readers ({@code ""}) or a tool. Data facts are named by
     * their section and label, prefixed with the tool when a tool reported them.
     */
    private void showData(String source, List<FactSection> sections) {
        List<DataRows.Root> roots = new ArrayList<>();
        for (FactSection section : sections) {
            for (Fact fact : section.facts()) {
                if (fact.kind() != Fact.Kind.DATA) continue;
                String name = section.title() + " › " + fact.label();
                roots.add(new DataRows.Root(source.isEmpty() ? name : source + " › " + name, fact.data()));
            }
        }
        if (roots.isEmpty()) this.dataBySource.remove(source);
        else this.dataBySource.put(source, roots);
        this.data.show(this.dataBySource.values().stream().flatMap(List::stream).toList());
    }

    /** The subject's rendered item for its editor tab, with a generic icon until the item can be drawn. */
    public ItemTabIcon tabIcon() {
        return this.tabIcon;
    }

    public void dispose() {
        requireEdt();
        this.disposed = true;
        this.removeIconListener.run();
        this.data.dispose();
        this.liveTimer.stop();
        this.tools.dispose();
        cancelActive();
    }

    private void cancelActive() {
        if (this.active != null) {
            this.active.cancel().run();
            this.active = null;
        }
    }

    private static JScrollPane scroll(JComponent content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private static DefaultListCellRenderer labels(Function<Object, String> text) {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                setText(text.apply(value));
                return this;
            }
        };
    }

    private static String capitalized(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1).toLowerCase(Locale.ROOT);
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
