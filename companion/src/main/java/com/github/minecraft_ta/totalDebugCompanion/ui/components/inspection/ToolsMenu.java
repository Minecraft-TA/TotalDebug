package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.inspection.InspectionSession;
import com.github.minecraft_ta.totalDebugCompanion.inspection.InspectionTool;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;

import javax.swing.BorderFactory;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The project tools of an inspection: the menu choosing and creating them, and their latest runs as sections. A tool's
 * reported sections are shown like the built-in ones, named after the tool; a section titled with the tool's name
 * holds its status and output when there is something to say.
 */
final class ToolsMenu {
    private ToolsMenu() {
    }

    /** The sections showing {@code reads}, or why the tools could not be loaded. */
    static List<FactsPanel.Part> parts(List<InspectionSession.ToolRead> reads, String problem) {
        List<FactsPanel.Part> parts = new ArrayList<>();
        if (!problem.isEmpty()) {
            parts.add(new FactsPanel.Part(new FactSection("Tools", List.of(Fact.problem("Status", Fact.clip(problem))), 1), null));
            return parts;
        }
        for (InspectionSession.ToolRead read : reads) {
            FactsPanel.Origin origin = new FactsPanel.Origin(read.tool().name(), read.tool().path());
            for (FactSection section : read.sections()) parts.add(new FactsPanel.Part(section, origin));
            List<Fact> status = new ArrayList<>();
            if (!read.failure().isEmpty()) status.add(Fact.problem("Status", Fact.clip(read.failure())));
            else if (read.sections().isEmpty()) status.add(Fact.text("Status", read.running() ? "Running…" : "No sections reported"));
            if (!read.output().isEmpty()) status.add(Fact.text("Output", Fact.clip(read.output())));
            if (!status.isEmpty()) {
                parts.add(new FactsPanel.Part(new FactSection(read.tool().name(), status, status.size()), origin));
            }
        }
        return parts;
    }

    /** The Tools menu: tools running with every inspection of the subject, other scripts to run here, and a new tool. */
    static JPopupMenu menu(InspectionSession session, JComponent owner, Consumer<NavigationTarget> navigator) {
        JPopupMenu menu = new JPopupMenu();
        String registryId = session.state().identity().registryId();
        List<InspectionTool> matching = session.tools().stream().filter(tool -> tool.appliesTo(registryId)).toList();
        List<InspectionTool> others = session.tools().stream().filter(tool -> !tool.appliesTo(registryId)).toList();
        if (!matching.isEmpty()) {
            menu.add(caption("Run with every inspection of " + registryId));
            for (InspectionTool tool : matching) {
                JMenuItem item = new JMenuItem(tool.name(), Icons.SCRIPT_FILE);
                item.setToolTipText(String.join(", ", tool.patterns()));
                item.addActionListener(event -> navigator.accept(new NavigationTarget.LocalFile(tool.path())));
                menu.add(item);
            }
            menu.addSeparator();
        }
        if (!others.isEmpty()) {
            menu.add(caption("Run in this tab"));
            for (InspectionTool tool : others) {
                JCheckBoxMenuItem item = new JCheckBoxMenuItem(tool.name(), session.chosen(tool.path()));
                item.addActionListener(event -> session.choose(tool.path(), item.isSelected()));
                menu.add(item);
            }
            menu.addSeparator();
        }
        JMenuItem create = new JMenuItem("New Tool for " + registryId + "…", Icons.SCRIPT_FILE);
        create.addActionListener(event -> createTool(session, owner, navigator));
        menu.add(create);
        return menu;
    }

    private static void createTool(InspectionSession session, JComponent owner, Consumer<NavigationTarget> navigator) {
        String registryId = session.state().identity().registryId();
        Object answer = JOptionPane.showInputDialog(owner, "Script name", "New Tool", JOptionPane.PLAIN_MESSAGE,
                null, null, suggestedName(registryId));
        if (answer == null || answer.toString().isBlank()) return;
        session.createTool(answer.toString().strip()).whenComplete((path, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure != null) {
                JOptionPane.showMessageDialog(owner, InspectionSession.rootMessage(failure), "New Tool", JOptionPane.ERROR_MESSAGE);
            } else {
                navigator.accept(new NavigationTarget.LocalFile(path));
            }
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

    private static JLabel caption(String text) {
        JLabel caption = new JLabel(text);
        ThemeColors.keepForeground(caption, ThemeColors::secondaryText);
        caption.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        return caption;
    }
}
