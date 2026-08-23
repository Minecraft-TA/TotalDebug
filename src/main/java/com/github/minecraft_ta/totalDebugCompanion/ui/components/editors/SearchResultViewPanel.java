package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.messages.codeView.DecompileOrOpenMessage;
import com.github.minecraft_ta.totalDebugCompanion.model.SearchResultView;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Function;
import java.util.function.Supplier;

public class SearchResultViewPanel extends JPanel {

    public SearchResultViewPanel(SearchResultView searchResultView) {
        setLayout(new BorderLayout(0, 0));
        JTable resultTable = new JTable(new DefaultTableModel(
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

        resultTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() < 2)
                    return;

                int row = resultTable.rowAtPoint(e.getPoint());
                if (row == -1)
                    return;

                var className = (String) resultTable.getValueAt(row, 0);
                className = className.replace('/', '.');

                CompanionApp.send(new DecompileOrOpenMessage(className));
            }
        });

        resultTable.addKeyListener(new KeyAdapter() {
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

        resultTable.getColumnModel().getColumn(0).setPreferredWidth(500);
        resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultTable.setFont(CodeViewPanel.JETBRAINS_MONO_FONT.deriveFont(14f));

        add(constructHeader(searchResultView), BorderLayout.NORTH);
        add(new JScrollPane(resultTable), BorderLayout.CENTER);
    }

    private Component constructHeader(SearchResultView view) {
        var box = Box.createHorizontalBox();

        Function<String, JLabel> label = (String s) -> {
            JLabel l = new JLabel(s);
            l.setFont(l.getFont().deriveFont(14f));
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
}
