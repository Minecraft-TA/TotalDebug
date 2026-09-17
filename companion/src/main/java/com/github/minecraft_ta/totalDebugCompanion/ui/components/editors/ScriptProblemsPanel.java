package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totaldebug.evaluation.CompilationDiagnostic;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Collections;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Compiler problems belong to the submitted source, not whichever text is currently in the editor. */
public final class ScriptProblemsPanel extends JPanel {
    private record Problem(CompilationDiagnostic diagnostic, int start, int end, String location) {
        String text() { return (location.isEmpty() ? "" : location + ": ") + diagnostic.message(); }
    }

    private final JTextComponent editor;
    private final DefaultListModel<Problem> model = new DefaultListModel<>();
    private final JList<Problem> list = new JList<>(model);
    private final JLabel outdated = new JLabel("Source changed since compilation", Icons.WARNING, SwingConstants.LEFT);
    private final Action jump;
    private String submittedSource;

    public ScriptProblemsPanel(JTextComponent editor) {
        super(new BorderLayout());
        this.editor = editor;
        outdated.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        outdated.setVisible(false);
        add(outdated, BorderLayout.NORTH);
        var scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> owner, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(owner, value, index, selected, focus);
                var problem = (Problem) value;
                setText(problem.text().replace('\n', ' ').replace('\r', ' '));
                setIcon(switch (problem.diagnostic().kind()) {
                    case ERROR -> Icons.ERROR;
                    case WARNING, MANDATORY_WARNING -> Icons.WARNING;
                    default -> Icons.INFORMATION;
                });
                setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
                return this;
            }
        });
        jump = ContextMenus.action("Jump to Source", Icons.JUMP_TO_SOURCE, "F4", this::navigate);
        ContextMenus.bindAction(list, jump);
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "jumpToProblem");
        list.getActionMap().put("jumpToProblem", jump);
        list.addListSelectionListener(event -> refreshSourceState());
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int row = list.locationToIndex(event.getPoint());
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 1 && row >= 0
                        && list.getCellBounds(row, row).contains(event.getPoint())) navigate();
            }
        });
        ContextMenus.installList(list, row -> {
            if (row < 0) return null;
            refreshSourceState();
            var menu = new JPopupMenu();
            menu.add(jump);
            menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy problem", model.get(row).text())));
            menu.add(ContextMenus.copyAction("Copy all problems",
                    Collections.list(model.elements()).stream().map(Problem::text).collect(Collectors.joining("\n"))));
            return menu;
        });
    }

    public void showProblems(JavaSnippetSource.GeneratedSource source, List<CompilationDiagnostic> diagnostics) {
        submittedSource = source.editorText();
        model.clear();
        for (var diagnostic : diagnostics) {
            var map = source.sourceMap();
            int last = Math.max(diagnostic.start(), diagnostic.end() - 1);
            int start = map.toEditorDiagnosticOffset(diagnostic.start(), diagnostic.syntaxError());
            int end = map.toEditorDiagnosticOffset(last, diagnostic.syntaxError());
            String location = "";
            if (start >= 0 && end >= start && start <= submittedSource.length()) {
                end = Math.min(submittedSource.length(), end + 1);
                int line = 1;
                for (int i = 0; i < start; i++) if (submittedSource.charAt(i) == '\n') line++;
                int column = start - submittedSource.lastIndexOf('\n', start - 1);
                location = "Line " + line + ":" + column;
            } else { start = -1; end = -1; }
            model.addElement(new Problem(diagnostic, start, end, location));
        }
        if (!model.isEmpty()) list.setSelectedIndex(0);
        refreshSourceState();
    }

    public void refreshSourceState() {
        boolean current = submittedSource != null && submittedSource.equals(editor.getText());
        outdated.setVisible(submittedSource != null && !current);
        var selected = list.getSelectedValue();
        jump.setEnabled(current && selected != null && selected.start() >= 0);
    }

    private void navigate() {
        refreshSourceState();
        if (!jump.isEnabled()) return;
        var problem = list.getSelectedValue();
        editor.requestFocusInWindow();
        editor.select(problem.start(), problem.end());
    }

    public void clear() {
        submittedSource = null;
        model.clear();
        refreshSourceState();
    }
}
