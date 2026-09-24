package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.inspection.InspectionTool;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionTextDisplay;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptSubject;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService.Side;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The project scripts run as tools on an inspected subject: every tool whose {@code // inspect:} patterns match the
 * subject, plus scripts chosen from the Tools menu for this tab. Each tool is its own run, so a failing tool reports
 * its error without affecting the others.
 */
final class ToolsPanel extends JPanel {
    private final InspectSubjectPayload subject;
    private final Supplier<SnippetExecutionService> snippets;
    private final Supplier<ScriptFiles> scripts;
    private final ItemIconService icons;
    private final Consumer<NavigationTarget> navigator;
    private final Runnable refreshInspection;
    private final Set<Path> chosen = new LinkedHashSet<>();
    private final List<SnippetExecutionService.Execution> active = new ArrayList<>();
    private final List<FactsPanel> results = new ArrayList<>();
    private List<InspectionTool> tools = List.of();
    private long revision;
    private boolean disposed;

    ToolsPanel(
            InspectSubjectPayload subject,
            Supplier<SnippetExecutionService> snippets,
            Supplier<ScriptFiles> scripts,
            ItemIconService icons,
            Consumer<NavigationTarget> navigator,
            Runnable refreshInspection
    ) {
        this.subject = subject;
        this.snippets = snippets;
        this.scripts = scripts;
        this.icons = icons;
        this.navigator = navigator;
        this.refreshInspection = refreshInspection;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }

    /** Loads the project's tools and runs those that apply, replacing earlier runs. */
    void run(Side side) {
        cancelActive();
        long current = ++this.revision;
        CompletableFuture.supplyAsync(this::loadTools).whenComplete((loaded, failure) -> SwingUtilities.invokeLater(() -> {
            if (this.disposed || current != this.revision) return;
            removeAll();
            this.results.clear();
            if (failure != null) {
                add(aligned(problemLabel("Tools could not be read: " + rootMessage(failure))));
            } else {
                this.tools = loaded;
                for (InspectionTool tool : loaded) {
                    if (tool.appliesTo(this.subject.registryId()) || this.chosen.contains(tool.path())) {
                        start(tool, side, current);
                    }
                }
            }
            revalidate();
            repaint();
        }));
    }

    private List<InspectionTool> loadTools() {
        try {
            return InspectionTool.load(this.scripts.get());
        } catch (Exception exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private void start(InspectionTool tool, Side side, long current) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        JLabel status = new JLabel("Running…");
        status.setForeground(UIManager.getColor("Label.disabledForeground"));
        section.add(aligned(toolHeader(tool, status)));
        add(aligned(section));
        add(Box.createVerticalStrut(8));

        JavaSnippetSource.GeneratedSource source;
        SnippetExecutionService.Execution execution;
        try {
            source = JavaSnippetSource.body(tool.name(), tool.text());
            source.requireExecutableSize();
            execution = this.snippets.get().execute(source, side, ScriptExecutionEnvironment.POST_TICK,
                    new ScriptSubject(SubjectRef.parse(this.subject.subject()), this.subject.gameSessionId()));
        } catch (RuntimeException exception) {
            showFailure(section, status, exception.getMessage());
            return;
        }
        this.active.add(execution);
        execution.completion().whenComplete((outcome, failure) -> SwingUtilities.invokeLater(() -> {
            this.active.remove(execution);
            if (this.disposed || current != this.revision) return;
            finish(section, status, source, outcome, failure);
        }));
    }

    private void finish(
            JPanel section,
            JLabel status,
            JavaSnippetSource.GeneratedSource source,
            ExecutionResult outcome,
            Throwable failure
    ) {
        if (failure != null) {
            showFailure(section, status, failure.getMessage());
            return;
        }
        if (outcome.status() != ExecutionStatus.RUN_COMPLETED) {
            String error = ExecutionTextDisplay.format(outcome.error());
            showFailure(section, status, error.isBlank() ? "The tool ended without a result" : source.mapDiagnostics(error));
            return;
        }
        status.setText(outcome.facts().isEmpty() ? "No sections reported" : "");
        if (!outcome.facts().isEmpty()) {
            FactsPanel facts = new FactsPanel(outcome.facts(), this.icons);
            facts.setBorder(null);
            this.results.add(facts);
            section.add(aligned(facts));
        }
        String logs = ExecutionTextDisplay.format(outcome.logs()).strip();
        if (!logs.isEmpty()) {
            JLabel output = new JLabel(logs.lines().findFirst().orElse(logs));
            output.setToolTipText(logs);
            section.add(aligned(output));
        }
        section.revalidate();
        section.repaint();
    }

    private void showFailure(JPanel section, JLabel status, String message) {
        String text = message == null || message.isBlank() ? "The tool failed" : message;
        status.setIcon(Icons.ERROR);
        status.setText(text.lines().findFirst().orElse(text));
        status.setToolTipText("<html><pre>" + escape(text) + "</pre></html>");
        section.revalidate();
    }

    private JComponent toolHeader(InspectionTool tool, JLabel status) {
        JLabel name = new JLabel(tool.name(), Icons.SCRIPT_FILE, JLabel.LEADING);
        name.putClientProperty("FlatLaf.styleClass", "h4");
        JButton edit = new JButton("Edit");
        edit.putClientProperty("JButton.buttonType", "borderless");
        edit.setToolTipText(tool.path().toString());
        edit.addActionListener(event -> this.navigator.accept(new NavigationTarget.LocalFile(tool.path())));
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        header.add(name);
        header.add(Box.createHorizontalStrut(6));
        header.add(edit);
        header.add(Box.createHorizontalStrut(6));
        header.add(status);
        return header;
    }

    /** The Tools menu: tools running automatically, other scripts to run on this subject, and a new tool. */
    JPopupMenu menu() {
        JPopupMenu menu = new JPopupMenu();
        List<InspectionTool> matching = this.tools.stream()
                .filter(tool -> tool.appliesTo(this.subject.registryId())).toList();
        List<InspectionTool> others = this.tools.stream()
                .filter(tool -> !tool.appliesTo(this.subject.registryId())).toList();
        if (!matching.isEmpty()) {
            menu.add(caption("Run with every inspection of " + this.subject.registryId()));
            for (InspectionTool tool : matching) {
                JMenuItem item = new JMenuItem(tool.name(), Icons.SCRIPT_FILE);
                item.setToolTipText(String.join(", ", tool.patterns()));
                item.addActionListener(event -> this.navigator.accept(new NavigationTarget.LocalFile(tool.path())));
                menu.add(item);
            }
            menu.addSeparator();
        }
        if (!others.isEmpty()) {
            menu.add(caption("Run in this tab"));
            for (InspectionTool tool : others) {
                JCheckBoxMenuItem item = new JCheckBoxMenuItem(tool.name(), this.chosen.contains(tool.path()));
                item.addActionListener(event -> {
                    if (item.isSelected()) this.chosen.add(tool.path());
                    else this.chosen.remove(tool.path());
                    this.refreshInspection.run();
                });
                menu.add(item);
            }
            menu.addSeparator();
        }
        JMenuItem create = new JMenuItem("New tool for " + this.subject.registryId() + "…", Icons.SCRIPT_FILE);
        create.addActionListener(event -> createTool());
        menu.add(create);
        return menu;
    }

    private void createTool() {
        String suggested = suggestedName(this.subject.registryId());
        Object answer = JOptionPane.showInputDialog(this, "Script name", "New tool", JOptionPane.PLAIN_MESSAGE,
                null, null, suggested);
        if (answer == null || answer.toString().isBlank()) return;
        String name = answer.toString().strip();
        String title = this.subject.displayName().isBlank() ? name : this.subject.displayName();
        CompletableFuture.supplyAsync(() -> {
            try {
                ScriptFiles files = this.scripts.get();
                Path folder = files.root().resolve(InspectionTool.FOLDER);
                if (!Files.isDirectory(folder)) {
                    folder = files.create(files.root(), InspectionTool.FOLDER, true, "");
                }
                return files.create(folder, name, false, InspectionTool.template(this.subject.registryId(), title));
            } catch (Exception exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
        }).whenComplete((path, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure != null) {
                JOptionPane.showMessageDialog(this, rootMessage(failure), "New tool", JOptionPane.ERROR_MESSAGE);
                return;
            }
            this.navigator.accept(new NavigationTarget.LocalFile(path));
            this.refreshInspection.run();
        }));
    }

    static String suggestedName(String registryId) {
        String path = registryId.substring(registryId.indexOf(':') + 1);
        StringBuilder name = new StringBuilder();
        for (String part : path.split("[^A-Za-z0-9]+")) {
            if (part.isEmpty()) continue;
            name.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        String result = name.append("Tool").toString();
        return Character.isJavaIdentifierStart(result.charAt(0)) ? result : "Tool" + result;
    }

    void reloadIcons() {
        this.results.forEach(FactsPanel::reloadIcons);
    }

    void dispose() {
        this.disposed = true;
        cancelActive();
    }

    private void cancelActive() {
        for (SnippetExecutionService.Execution execution : List.copyOf(this.active)) {
            execution.cancel().run();
        }
        this.active.clear();
    }

    private static JLabel caption(String text) {
        JLabel caption = new JLabel(text);
        caption.setForeground(UIManager.getColor("Label.disabledForeground"));
        caption.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        return caption;
    }

    private static JLabel problemLabel(String text) {
        return new JLabel(text, Icons.ERROR, JLabel.LEADING);
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName());
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static <T extends JComponent> T aligned(T component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }
}
