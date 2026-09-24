package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionTextDisplay;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptSubject;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService.Side;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.values.ScriptResultTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;

import javax.swing.BorderFactory;
import javax.swing.Box;
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
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Shows a block or entity selected in the game. Each refresh runs the built-in readers against {@code target()} on
 * the chosen side through the ordinary snippet path, so it sees exactly what a script bound to the same subject sees.
 * The readers' fact sections form the overview; the returned object remains available for code-level inspection.
 * Every read reports what currently occupies the subject: the header, the tab and the tools follow it, and a
 * replaced block is called out. A read that fails keeps the facts it did report, and a failed refresh keeps the
 * previous read on screen, marked as stale.
 */
public final class InspectionPanel extends JPanel {
    static final List<String> FACES = List.of("", "DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST");
    private static final String RESULT_CARD = "result";
    private static final String PROBLEM_CARD = "problem";
    private static final int HEADER_ICON_SIZE = 48;
    private static final int TAB_ICON_SIZE = 32;
    private static final DateTimeFormatter CAPTURE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    static final List<Integer> LIVE_INTERVALS_MS = List.of(500, 1_000, 2_000, 5_000);

    private final InspectSubjectPayload subject;
    private final Consumer<NavigationTarget> navigator;
    private final Supplier<SnippetExecutionService> snippets;
    private final ItemIconService icons;
    private final Runnable removeIconListener;
    private final JLabel icon = new JLabel();
    private final JLabel name = new JLabel();
    private final JLabel identityLine = new JLabel();
    private final JLabel notice = new JLabel();
    private final JPanel classes = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final ItemTabIcon tabIcon = new ItemTabIcon(Icons.EVALUATE_EXPRESSION);
    private final JComboBox<Side> runSide = new JComboBox<>(new Side[]{Side.SERVER, Side.CLIENT});
    private final JComboBox<String> face = new JComboBox<>(FACES.toArray(String[]::new));
    private final JButton refresh = new JButton("Refresh", Icons.REFRESH);
    private final JButton toolsButton = new JButton("Tools", Icons.SCRIPT_FILE);
    private final JToggleButton live = new JToggleButton("Live");
    private final JComboBox<Integer> liveInterval = new JComboBox<>(LIVE_INTERVALS_MS.toArray(Integer[]::new));
    private final Timer liveTimer = new Timer(1_000, event -> liveTick());
    private final ToolsPanel tools;
    private final JPanel builtIn = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel(" ");
    private final JPanel overview = new JPanel(new BorderLayout());
    private final ScriptResultTree object = new ScriptResultTree();
    private final DataView data = new DataView();
    private final Map<String, List<DataRows.Root>> dataBySource = new LinkedHashMap<>();
    private final JTextArea problem = new JTextArea();
    private final JPanel cards = new JPanel(new CardLayout());
    private FactsPanel facts;
    private SubjectIdentity identity;
    private String lastRead;
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
                icons, navigator, this::refresh, this::showToolData);
        JPanel sections = new JPanel();
        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
        this.builtIn.setAlignmentX(Component.LEFT_ALIGNMENT);
        this.tools.setAlignmentX(Component.LEFT_ALIGNMENT);
        this.tools.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        sections.add(this.builtIn);
        sections.add(this.tools);
        this.overview.add(sections, BorderLayout.NORTH);

        add(header(), BorderLayout.NORTH);
        JTabbedPane views = new JTabbedPane();
        views.addTab("Overview", scroll(this.overview));
        views.addTab("Data", this.data);
        views.addTab("Object", new JScrollPane(this.object));
        this.problem.setEditable(false);
        this.cards.add(views, RESULT_CARD);
        this.cards.add(new JScrollPane(this.problem), PROBLEM_CARD);
        add(this.cards, BorderLayout.CENTER);

        this.runSide.setRenderer(labels(value -> value == Side.CLIENT ? "Client" : "Server"));
        this.runSide.setToolTipText("Read the server's or the client's copy of the world");
        this.face.setRenderer(labels(value -> sideLabel(value == null ? "" : value.toString())));
        this.face.setToolTipText("<html>Side passed to capability queries.<br>None asks for the unsided handler, "
                + "which the mod defines; it is not a combination of the six faces.</html>");
        this.runSide.addActionListener(event -> refresh());
        this.face.addActionListener(event -> refresh());
        this.refresh.addActionListener(event -> refresh());
        this.liveInterval.setSelectedItem(1_000);
        this.liveInterval.setEnabled(false);
        this.liveInterval.setRenderer(labels(value -> value instanceof Integer millis
                ? (millis % 1_000 == 0 ? millis / 1_000 + " s" : millis / 1_000.0 + " s") : ""));
        this.liveInterval.setToolTipText("Time between reads while Live is on");
        this.live.setToolTipText("Read again automatically while this tab is visible");
        this.live.addActionListener(event -> {
            this.liveInterval.setEnabled(this.live.isSelected());
            if (this.live.isSelected()) refresh();
            else this.liveTimer.stop();
        });
        this.liveTimer.setRepeats(false);
        this.toolsButton.setToolTipText("Project scripts run on this subject");
        this.toolsButton.addActionListener(event ->
                this.tools.menu().show(this.toolsButton, 0, this.toolsButton.getHeight()));
        this.removeIconListener = icons.addListener(this::reloadIcons);
        reloadIcons();
    }

    private JComponent header() {
        this.name.putClientProperty("FlatLaf.styleClass", "h3");

        Box title = Box.createHorizontalBox();
        title.add(this.name);
        title.add(Box.createHorizontalGlue());
        this.face.setMaximumSize(this.face.getPreferredSize());
        this.runSide.setMaximumSize(this.runSide.getPreferredSize());
        this.liveInterval.setMaximumSize(this.liveInterval.getPreferredSize());
        title.add(this.face);
        title.add(Box.createHorizontalStrut(6));
        title.add(this.runSide);
        title.add(Box.createHorizontalStrut(6));
        title.add(this.live);
        title.add(Box.createHorizontalStrut(4));
        title.add(this.liveInterval);
        title.add(Box.createHorizontalStrut(6));
        title.add(this.toolsButton);
        title.add(Box.createHorizontalStrut(6));
        title.add(this.refresh);

        this.identityLine.putClientProperty("FlatLaf.styleClass", "small");
        this.notice.putClientProperty("FlatLaf.styleClass", "small");
        this.notice.setIcon(Icons.WARNING);
        this.notice.setVisible(false);
        showIdentity();

        JPanel details = new JPanel();
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        for (JComponent row : new JComponent[]{title, this.identityLine, this.notice, this.classes, this.status}) {
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        details.add(title);
        details.add(Box.createVerticalStrut(4));
        details.add(this.identityLine);
        details.add(this.notice);
        details.add(Box.createVerticalStrut(4));
        details.add(this.classes);
        details.add(Box.createVerticalStrut(4));
        details.add(this.status);

        this.icon.setPreferredSize(new Dimension(HEADER_ICON_SIZE, HEADER_ICON_SIZE));
        this.icon.setHorizontalAlignment(SwingConstants.CENTER);
        this.icon.setVerticalAlignment(SwingConstants.TOP);
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.add(this.icon, BorderLayout.WEST);
        header.add(details, BorderLayout.CENTER);
        header.setBorder(new CompoundBorder(
                DynamicMatteBorder.separatorRule(0, 0, 1, 0),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        return header;
    }

    /** Shows the current identity in the header: name, registry id, mod, subject and class links. */
    private void showIdentity() {
        this.name.setText(this.identity.title());
        StringBuilder text = new StringBuilder(this.identity.registryId());
        if (!this.identity.modName().isBlank()) {
            text.append("  ·  ").append(this.identity.modName());
        }
        this.identityLine.setText(text.append("  ·  ").append(this.subject.subject()).toString());
        this.classes.removeAll();
        for (SubjectIdentity.ClassLink link : this.identity.classes()) {
            JButton button = new JButton(link.label() + ": " + simpleName(link.binaryName()), Icons.JAVA_CLASS);
            button.putClientProperty("JButton.buttonType", "borderless");
            button.setToolTipText(link.binaryName());
            button.addActionListener(event ->
                    this.navigator.accept(new NavigationTarget.RuntimeClass(link.binaryName())));
            this.classes.add(button);
        }
        this.classes.revalidate();
        this.classes.repaint();
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
        try {
            this.active = this.snippets.get().execute(
                    source,
                    selectedSide,
                    ScriptExecutionEnvironment.POST_TICK,
                    new ScriptSubject(SubjectRef.parse(this.subject.subject()), this.subject.gameSessionId())
            );
        } catch (RuntimeException exception) {
            readFailed(exception.getMessage());
            scheduleLive(current);
            return;
        }
        if (!this.live.isSelected()) {
            this.refresh.setEnabled(false);
            this.status.setIcon(null);
            this.status.setText("Reading on " + sideName(selectedSide) + "…");
        }
        CompletableFuture<Void> toolsDone = this.tools.run(selectedSide);
        CompletableFuture<Void> builtInDone = this.active.completion().handle((outcome, failure) -> {
            SwingUtilities.invokeLater(() -> finish(current, selectedSide, source, outcome, failure));
            return null;
        });
        CompletableFuture.allOf(builtInDone, toolsDone).whenComplete((ignored, failure) ->
                SwingUtilities.invokeLater(() -> scheduleLive(current)));
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
        showOutcome(outcome, selectedSide);
        String logs = ExecutionTextDisplay.format(outcome.logs()).strip();
        if (!completed) {
            showStatusProblem(this.status.getText() + "  ·  " + firstLine(error), error + "\n\n" + logs);
        } else if (hasProblems(outcome)) {
            showStatusProblem(this.status.getText() + "  ·  Some parts could not be read", logs);
        }
    }

    /**
     * Takes the identity a read reported. A different registry id means the subject was replaced: the header says
     * what it was, and the tools are selected again for what is there now.
     */
    void applyIdentity(SubjectIdentity reported, Side selectedSide) {
        if (reported.equals(this.identity)) return;
        boolean replaced = !reported.registryId().equals(this.identity.registryId());
        if (replaced) {
            this.notice.setText("Was " + this.identity.title() + " (" + this.identity.registryId() + ") until "
                    + LocalTime.now().format(CAPTURE_TIME));
            this.notice.setVisible(true);
        }
        this.identity = reported;
        showIdentity();
        reloadIcons();
        EditorTabs tabs = (EditorTabs) SwingUtilities.getAncestorOfClass(EditorTabs.class, this);
        if (tabs != null) tabs.refreshEditorTitles();
        if (replaced) {
            this.tools.run(selectedSide);
        }
    }

    /** Presents a completed read's sections and returned object. */
    void showOutcome(ExecutionResult outcome, Side selectedSide) {
        showData("", outcome.facts());
        if (this.facts == null || !this.facts.update(outcome.facts())) {
            this.facts = new FactsPanel(outcome.facts(), this.icons);
            this.builtIn.removeAll();
            this.builtIn.add(this.facts, BorderLayout.CENTER);
            this.overview.revalidate();
            this.overview.repaint();
        }
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
        this.lastRead = LocalTime.now().format(CAPTURE_TIME);
        this.status.setIcon(null);
        this.status.setToolTipText(null);
        this.status.setText((this.live.isSelected() ? "Live · " : "") + capitalized(sideName(selectedSide))
                + " · " + this.lastRead);
    }

    /**
     * Reports a read that produced nothing to show. The previous read stays on screen, marked with the time it was
     * taken; without one the problem replaces the overview.
     */
    private void readFailed(String message) {
        String text = message == null || message.isBlank() ? "The read failed" : message;
        this.refresh.setEnabled(true);
        if (this.lastRead != null) {
            showStatusProblem("Last read " + this.lastRead + "  ·  " + firstLine(text), text);
            return;
        }
        this.problem.setText(text);
        this.problem.setCaretPosition(0);
        ((CardLayout) this.cards.getLayout()).show(this.cards, PROBLEM_CARD);
        showStatusProblem(firstLine(text), text);
    }

    private void showStatusProblem(String text, String details) {
        this.status.setIcon(Icons.ERROR);
        this.status.setText(text);
        String bounded = details.length() > 4_000 ? details.substring(0, 4_000) + "…" : details;
        this.status.setToolTipText("<html><pre>" + escape(bounded.strip()) + "</pre></html>");
    }

    private static boolean hasProblems(ExecutionResult outcome) {
        return outcome.facts().stream().flatMap(section -> section.facts().stream())
                .anyMatch(fact -> fact.kind() == Fact.Kind.PROBLEM);
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse(text);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void reloadIcons() {
        if (this.disposed) return;
        if (this.facts != null) {
            this.facts.reloadIcons();
        }
        this.tools.reloadIcons();
        // The client's icon has the selected item's tints; after a replacement only the plain item model is known.
        boolean selectedItem = this.identity.iconItem().equals(this.subject.identity().iconItem());
        String model = selectedItem ? this.subject.iconModel()
                : this.identity.iconItem().isEmpty() ? "" : ItemIconService.itemModel(this.identity.iconItem());
        Map<Integer, Integer> tints = selectedItem ? this.subject.iconTints() : Map.of();
        this.icons.render(model, tints, HEADER_ICON_SIZE)
                .thenAccept(image -> SwingUtilities.invokeLater(() -> {
                    if (!this.disposed) this.icon.setIcon(image.map(ImageIcon::new).orElse(null));
                }));
        this.icons.render(model, tints, TAB_ICON_SIZE)
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
     * their label, prefixed with the section, and with the tool when a tool reported them.
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

    private static String sideName(Side side) {
        return side == Side.CLIENT ? "client" : "server";
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
