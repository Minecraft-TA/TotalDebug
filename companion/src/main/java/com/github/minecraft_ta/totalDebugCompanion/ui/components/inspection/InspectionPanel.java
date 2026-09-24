package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionTextDisplay;
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
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shows the live object behind an inspected block or entity. Each refresh runs {@code target()} on the selected side
 * through the ordinary snippet path, so it sees exactly what a script bound to the same subject sees.
 */
public final class InspectionPanel extends JPanel {
    static final String EXPRESSION = "target()";
    private static final String RESULT_CARD = "result";
    private static final String PROBLEM_CARD = "problem";
    private static final DateTimeFormatter CAPTURE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final InspectSubjectPayload subject;
    private final Supplier<SnippetExecutionService> snippets;
    private final JComboBox<Side> side = new JComboBox<>(new Side[]{Side.SERVER, Side.CLIENT});
    private final JButton refresh = new JButton("Refresh", Icons.REFRESH);
    private final JLabel status = new JLabel(" ");
    private final ScriptResultTree result = new ScriptResultTree();
    private final JTextArea problem = new JTextArea();
    private final JPanel cards = new JPanel(new CardLayout());
    private SnippetExecutionService.Execution active;
    private long revision;
    private boolean disposed;

    public InspectionPanel(
            InspectSubjectPayload subject,
            Supplier<SnippetExecutionService> snippets,
            Consumer<NavigationTarget> navigator
    ) {
        super(new BorderLayout());
        this.subject = Objects.requireNonNull(subject, "subject");
        this.snippets = Objects.requireNonNull(snippets, "snippets");
        Objects.requireNonNull(navigator, "navigator");

        add(header(navigator), BorderLayout.NORTH);
        this.problem.setEditable(false);
        this.problem.setLineWrap(false);
        this.cards.add(new JScrollPane(this.result), RESULT_CARD);
        this.cards.add(new JScrollPane(this.problem), PROBLEM_CARD);
        add(this.cards, BorderLayout.CENTER);

        this.side.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                setText(value == Side.CLIENT ? "Client" : "Server");
                return this;
            }
        });
        this.side.setToolTipText("Side whose copy of the world is read");
        this.side.addActionListener(event -> refresh());
        this.refresh.addActionListener(event -> refresh());
    }

    private JPanel header(Consumer<NavigationTarget> navigator) {
        JLabel name = new JLabel(this.subject.displayName().isBlank()
                ? this.subject.registryId() : this.subject.displayName());
        name.putClientProperty("FlatLaf.styleClass", "h3");

        Box title = Box.createHorizontalBox();
        title.add(name);
        title.add(Box.createHorizontalGlue());
        title.add(this.side);
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

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        for (JComponent row : new JComponent[]{title, identity, classes, this.status}) {
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(identity);
        header.add(Box.createVerticalStrut(4));
        header.add(classes);
        header.add(Box.createVerticalStrut(4));
        header.add(this.status);
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

    /** Starts a new read of the subject, replacing one still running. */
    public void refresh() {
        requireEdt();
        if (this.disposed) return;
        cancelActive();
        Side selectedSide = (Side) this.side.getSelectedItem();
        JavaSnippetSource.GeneratedSource source = JavaSnippetSource.expression("InspectTarget", EXPRESSION);
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
        this.status.setText("Reading on " + sideName(selectedSide) + "…");
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
        this.result.showResult(outcome.value());
        this.result.expandRow(0);
        ((CardLayout) this.cards.getLayout()).show(this.cards, RESULT_CARD);
        this.status.setIcon(null);
        this.status.setText(sideName(selectedSide) + " · " + LocalTime.now().format(CAPTURE_TIME));
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

    public void dispose() {
        requireEdt();
        this.disposed = true;
        cancelActive();
    }

    private void cancelActive() {
        if (this.active != null) {
            this.active.cancel().run();
            this.active = null;
        }
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
