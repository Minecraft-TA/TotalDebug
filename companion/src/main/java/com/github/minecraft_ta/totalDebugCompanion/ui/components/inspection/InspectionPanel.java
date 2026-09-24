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
import com.github.minecraft_ta.totalDebugCompanion.ui.components.values.ScriptResultTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
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
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shows a block or entity selected in the game. Each refresh runs the built-in readers against {@code target()} on
 * the chosen side through the ordinary snippet path, so it sees exactly what a script bound to the same subject sees.
 * The readers' fact sections form the overview; the returned object remains available for code-level inspection.
 */
public final class InspectionPanel extends JPanel {
    static final List<String> FACES = List.of("", "DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST");
    private static final String RESULT_CARD = "result";
    private static final String PROBLEM_CARD = "problem";
    private static final int HEADER_ICON_SIZE = 48;
    private static final int TAB_ICON_SIZE = 32;
    private static final DateTimeFormatter CAPTURE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final InspectSubjectPayload subject;
    private final Supplier<SnippetExecutionService> snippets;
    private final ItemIconService icons;
    private final Runnable removeIconListener;
    private final JLabel icon = new JLabel();
    private final ItemTabIcon tabIcon = new ItemTabIcon(Icons.EVALUATE_EXPRESSION);
    private final JComboBox<Side> runSide = new JComboBox<>(new Side[]{Side.SERVER, Side.CLIENT});
    private final JComboBox<String> face = new JComboBox<>(FACES.toArray(String[]::new));
    private final JButton refresh = new JButton("Refresh", Icons.REFRESH);
    private final JButton toolsButton = new JButton("Tools", Icons.SCRIPT_FILE);
    private final ToolsPanel tools;
    private final JPanel builtIn = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel(" ");
    private final JPanel overview = new JPanel(new BorderLayout());
    private final ScriptResultTree object = new ScriptResultTree();
    private final JTextArea problem = new JTextArea();
    private final JPanel cards = new JPanel(new CardLayout());
    private FactsPanel facts;
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
        this.snippets = Objects.requireNonNull(snippets, "snippets");
        this.icons = Objects.requireNonNull(icons, "icons");
        Objects.requireNonNull(navigator, "navigator");
        this.tools = new ToolsPanel(subject, snippets, Objects.requireNonNull(scripts, "scripts"), icons, navigator,
                this::refresh);
        JPanel sections = new JPanel();
        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
        this.builtIn.setAlignmentX(Component.LEFT_ALIGNMENT);
        this.tools.setAlignmentX(Component.LEFT_ALIGNMENT);
        this.tools.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        sections.add(this.builtIn);
        sections.add(this.tools);
        this.overview.add(sections, BorderLayout.NORTH);

        add(header(navigator), BorderLayout.NORTH);
        JTabbedPane views = new JTabbedPane();
        views.addTab("Overview", scroll(this.overview));
        views.addTab("Object", new JScrollPane(this.object));
        this.problem.setEditable(false);
        this.cards.add(views, RESULT_CARD);
        this.cards.add(new JScrollPane(this.problem), PROBLEM_CARD);
        add(this.cards, BorderLayout.CENTER);

        this.runSide.setRenderer(labels(value -> value == Side.CLIENT ? "Client" : "Server"));
        this.runSide.setToolTipText("Read the server's or the client's copy of the world");
        this.face.setRenderer(labels(value -> value == null || value.toString().isEmpty()
                ? "All sides" : capitalized(value.toString())));
        this.face.setToolTipText("Face whose item, fluid and energy handlers are read");
        this.runSide.addActionListener(event -> refresh());
        this.face.addActionListener(event -> refresh());
        this.refresh.addActionListener(event -> refresh());
        this.toolsButton.setToolTipText("Project scripts run on this subject");
        this.toolsButton.addActionListener(event ->
                this.tools.menu().show(this.toolsButton, 0, this.toolsButton.getHeight()));
        this.removeIconListener = icons.addListener(this::reloadIcons);
        reloadIcons();
    }

    private JComponent header(Consumer<NavigationTarget> navigator) {
        JLabel name = new JLabel(this.subject.displayName().isBlank()
                ? this.subject.registryId() : this.subject.displayName());
        name.putClientProperty("FlatLaf.styleClass", "h3");

        Box title = Box.createHorizontalBox();
        title.add(name);
        title.add(Box.createHorizontalGlue());
        this.face.setMaximumSize(this.face.getPreferredSize());
        this.runSide.setMaximumSize(this.runSide.getPreferredSize());
        title.add(this.face);
        title.add(Box.createHorizontalStrut(6));
        title.add(this.runSide);
        title.add(Box.createHorizontalStrut(6));
        title.add(this.toolsButton);
        title.add(Box.createHorizontalStrut(6));
        title.add(this.refresh);

        JLabel identity = new JLabel(identity());
        identity.putClientProperty("FlatLaf.styleClass", "small");

        JPanel classes = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (InspectSubjectPayload.ClassLink link : this.subject.classes()) {
            JButton button = new JButton(link.label() + ": " + simpleName(link.binaryName()), Icons.JAVA_CLASS);
            button.putClientProperty("JButton.buttonType", "borderless");
            button.setToolTipText(link.binaryName());
            button.addActionListener(event -> navigator.accept(new NavigationTarget.RuntimeClass(link.binaryName())));
            classes.add(button);
        }

        JPanel details = new JPanel();
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        for (JComponent row : new JComponent[]{title, identity, classes, this.status}) {
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        details.add(title);
        details.add(Box.createVerticalStrut(4));
        details.add(identity);
        details.add(Box.createVerticalStrut(4));
        details.add(classes);
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

    private String identity() {
        StringBuilder text = new StringBuilder(this.subject.registryId());
        if (!this.subject.modName().isBlank()) {
            text.append("  ·  ").append(this.subject.modName());
        }
        return text.append("  ·  ").append(this.subject.subject()).toString();
    }

    /** The snippet each refresh runs: the built-in readers report sections, and the target is the result. */
    static String readerSource(String faceName) {
        String side = faceName.isEmpty() ? "null" : "Direction." + faceName;
        return """
                import com.github.minecraft_ta.totaldebug.inspection.CapabilityReader;
                import com.github.minecraft_ta.totaldebug.inspection.NbtReader;
                import com.github.minecraft_ta.totaldebug.inspection.StorageReader;
                import net.minecraft.core.Direction;
                StorageReader.read(target(), %1$s, facts());
                CapabilityReader.read(target(), %1$s, facts());
                NbtReader.read(target(), facts());
                return target();
                """.formatted(side);
    }

    /** Starts a new read of the subject, replacing one still running. */
    public void refresh() {
        requireEdt();
        if (this.disposed) return;
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
            showProblem(exception.getMessage());
            return;
        }
        this.refresh.setEnabled(false);
        this.status.setIcon(null);
        this.status.setText("Reading on " + sideName(selectedSide) + "…");
        this.tools.run(selectedSide);
        this.active.completion().whenComplete((outcome, failure) -> SwingUtilities.invokeLater(() ->
                finish(current, selectedSide, source, outcome, failure)));
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
        this.refresh.setEnabled(true);
        if (failure != null) {
            showProblem(failure.getMessage());
            return;
        }
        if (outcome.status() != ExecutionStatus.RUN_COMPLETED || outcome.value() == null) {
            String error = ExecutionTextDisplay.format(outcome.error());
            showProblem(error.isBlank() ? "The read ended without a result" : source.mapDiagnostics(error));
            return;
        }
        showOutcome(outcome, selectedSide);
    }

    /** Presents a completed read's sections and returned object. */
    void showOutcome(ExecutionResult outcome, Side selectedSide) {
        this.facts = new FactsPanel(outcome.facts(), this.icons);
        this.builtIn.removeAll();
        this.builtIn.add(this.facts, BorderLayout.CENTER);
        this.overview.revalidate();
        this.overview.repaint();
        this.object.showResult(outcome.value());
        this.object.expandRow(0);
        ((CardLayout) this.cards.getLayout()).show(this.cards, RESULT_CARD);
        this.status.setText(capitalized(sideName(selectedSide)) + " · " + LocalTime.now().format(CAPTURE_TIME));
    }

    private void showProblem(String message) {
        String text = message == null || message.isBlank() ? "The read failed" : message;
        this.refresh.setEnabled(true);
        this.problem.setText(text);
        this.problem.setCaretPosition(0);
        ((CardLayout) this.cards.getLayout()).show(this.cards, PROBLEM_CARD);
        this.status.setIcon(Icons.ERROR);
        this.status.setText(text.lines().findFirst().orElse(text));
    }

    private void reloadIcons() {
        if (this.disposed) return;
        if (this.facts != null) {
            this.facts.reloadIcons();
        }
        this.tools.reloadIcons();
        this.icons.render(this.subject.iconModel(), this.subject.iconTints(), HEADER_ICON_SIZE)
                .thenAccept(image -> SwingUtilities.invokeLater(() -> {
                    if (!this.disposed) this.icon.setIcon(image.map(ImageIcon::new).orElse(null));
                }));
        this.icons.render(this.subject.iconModel(), this.subject.iconTints(), TAB_ICON_SIZE)
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
        this.removeIconListener.run();
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
