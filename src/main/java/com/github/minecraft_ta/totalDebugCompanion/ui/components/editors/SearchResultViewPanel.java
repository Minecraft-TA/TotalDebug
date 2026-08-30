package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.model.SearchResultView;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.HierarchyEvent;
import java.beans.PropertyChangeListener;
import java.util.function.Function;
import java.util.function.Supplier;

public class SearchResultViewPanel extends JPanel {

    private final JTable resultTable;
    private final PropertyChangeListener editorFontListener = event -> updateEditorFont();

    public SearchResultViewPanel(SearchResultView searchResultView) {
        setLayout(new BorderLayout(0, 0));
        this.resultTable = new JTable(new DefaultTableModel(
                searchResultView.getResults().stream()
                        .sorted()
                        .map(r -> {
                            String[] ar = r.split("#");
                            return new Object[]{ar[0], ar[1]};
                        }).toArray(size -> new Object[size][1]),
                new Object[]{"Class", "Method"}
        )) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        this.resultTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() < 2)
                    return;

                int row = resultTable.rowAtPoint(e.getPoint());
                if (row == -1)
                    return;

                var className = (String) resultTable.getValueAt(row, 0);
                className = className.replace('/', '.');

                CompanionApp.openClass(className);
            }
        });

        this.resultTable.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                var selectedRow = resultTable.getSelectedRow();
                if (e.getKeyChar() != KeyEvent.VK_DELETE || selectedRow == -1)
                    return;

                DefaultTableModel tableModel = (DefaultTableModel) resultTable.getModel();
                tableModel.removeRow(selectedRow);

                if (resultTable.getRowCount() > 0)
                    resultTable.setRowSelectionInterval(0, selectedRow < 1 ? 0 : selectedRow - 1);
            }
        });

        this.resultTable.getColumnModel().getColumn(0).setPreferredWidth(500);
        this.resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        SpeedSearch.install(this.resultTable, row -> {
            StringBuilder text = new StringBuilder();
            for (int column = 0; column < this.resultTable.getColumnCount(); column++) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(this.resultTable.getValueAt(row, column));
            }
            return text.toString();
        });
        this.resultTable.getInputMap(WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openSearchResult");
        this.resultTable.getActionMap().put("openSearchResult", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                openSelectedResult();
            }
        });
        updateEditorFont();
        GlobalConfig.getInstance().addEditorFontSizeListener(this.editorFontListener);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0 && getParent() == null) {
                GlobalConfig.getInstance().removeEditorFontSizeListener(this.editorFontListener);
            }
        });

        add(constructHeader(searchResultView), BorderLayout.NORTH);
        add(new JScrollPane(this.resultTable), BorderLayout.CENTER);
    }

    private void openSelectedResult() {
        int row = this.resultTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        String className = ((String) this.resultTable.getValueAt(row, 0)).replace('/', '.');
        CompanionApp.openClass(className);
    }

    private Component constructHeader(SearchResultView view) {
        var box = Box.createHorizontalBox();

        Function<String, JLabel> label = (String s) -> {
            JLabel l = new JLabel(s);
            return UIUtils.withBorder(l, BorderFactory.createEmptyBorder(5, 5, 5, 5));
        };

        Supplier<JSeparator> separator = () -> {
            var sep = new JSeparator(JSeparator.VERTICAL);
            sep.setMaximumSize(new Dimension(5, 20));
            return sep;
        };

        box.add(label.apply("Query: " + view.getQuery()));
        box.add(separator.get());
        box.add(label.apply("Result count: " + view.getResults().size()));
        box.add(separator.get());
        box.add(label.apply("Search type: " + (view.isMethodSearch() ? "Method" : "Field")));
        box.add(separator.get());
        box.add(label.apply("Classes scanned: " + view.getClassesCount()));
        box.add(separator.get());
        box.add(label.apply("Time: " + view.getTime() + "ms"));

        box.setBorder(BorderFactory.createTitledBorder("Stats"));
        return box;
    }

    private void updateEditorFont() {
        Font font = CodeViewPanel.JETBRAINS_MONO_FONT.deriveFont(GlobalConfig.getInstance().editorFontSize());
        this.resultTable.setFont(font);
        this.resultTable.setRowHeight(Math.max(
                UiMetrics.TREE_ROW_HEIGHT,
                this.resultTable.getFontMetrics(font).getHeight() + 6
        ));
    }
}
