package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.messages.codeView.DecompileOrOpenMessage;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.tth05.jindex.IndexedClass;
import com.github.tth05.jindex.SearchOptions;

import javax.swing.*;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Collectors;

public class SearchEverywherePopup extends JFrame {

    private final JList<IndexedClass> resultList = new JList<>(new DefaultListModel<>());
    {
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.addListSelectionListener(e -> {
            var rect = resultList.getCellBounds(e.getFirstIndex(), e.getLastIndex());
            if (rect == null)
                return;

            resultList.scrollRectToVisible(rect);
        });

        resultList.setCellRenderer(new DefaultListCellRenderer() {

            private final int spaceWidth = resultList.getFontMetrics(resultList.getFont()).stringWidth(" ");

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, null, index, isSelected, cellHasFocus);

                var indexedClass = (IndexedClass) value;

                var p = primaryLabel(indexedClass.getName());
                var s = secondaryLabel(indexedClass.getPackage().getNameWithParentsDot());

                if (Modifier.isInterface(indexedClass.getAccessFlags()))
                    p.setIcon(Icons.JAVA_INTERFACE);
                else if ((indexedClass.getAccessFlags() & 0x00004000) != 0)
                    p.setIcon(Icons.JAVA_ENUM);
                else
                    p.setIcon(Icons.JAVA_CLASS);

                var layout = UIUtils.horizontalLayout(p, Box.createHorizontalStrut(spaceWidth), s);
                layout.setBorder(new CompoundBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0), getBorder()));
                layout.setBackground(getBackground());
                layout.setOpaque(true);
                return layout;
            }
        });
        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() < 2 || !SwingUtilities.isLeftMouseButton(e))
                    return;

                var clickedIndex = resultList.locationToIndex(e.getPoint());
                if (clickedIndex == -1 || !resultList.getCellBounds(0, resultList.getLastVisibleIndex()).contains(e.getPoint()))
                    return;

                openClass(clickedIndex);
            }
        });
        resultList.setSelectionBackground(UIManager.getColor("List.selectionBackground"));
    }
    private final JScrollPane resultListScrollPane = new JScrollPane(this.resultList);
    {
        resultListScrollPane.setPreferredSize(new Dimension(500, 500));
        resultListScrollPane.setBorder(DynamicMatteBorder.rule(1, 0, 0, 0));
    }

    private final FlatIconTextField searchTextField = new FlatIconTextField(Icons.SEARCH_ICON);
    {
        searchTextField.getDocument().addDocumentListener((DocumentChangeListener) e -> {
            var query = searchTextField.getText();
            var model = (DefaultListModel<IndexedClass>) resultList.getModel();
            if (query == null || query.isBlank()) {
                model.clear();
                return;
            }

            var classes = Arrays
                    .stream(CompanionClassIndex.get().findClasses(query, SearchOptions.with(SearchOptions.SearchMode.CONTAINS, SearchOptions.MatchMode.IGNORE_CASE, 800)))
                    // Down-rank inner classes
                    .sorted((a, b) -> a.getInnerClassType() != null ? b.getInnerClassType() != null ? 0 : 1 : b.getInnerClassType() != null ? -1 : 0)
                    .collect(Collectors.toList());

            var selectedClass = resultList.getSelectedIndex() == -1 ? null : model.get(resultList.getSelectedIndex()).getNameWithPackage();

            model.clear();
            model.addAll(classes);

            for (int i = 0; i < classes.size() && selectedClass != null; i++) {
                if (!classes.get(i).getNameWithPackage().equals(selectedClass))
                    continue;

                resultList.setSelectedIndex(i);
                return;
            }

            resultList.setSelectedIndex(0);
        });
        ((AbstractDocument) searchTextField.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
                if (!string.chars().allMatch(i -> i < 128))
                    return;

                super.insertString(fb, offset, string, attr);
            }

            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                if (!text.chars().allMatch(i -> i < 128))
                    return;

                super.replace(fb, offset, length, text, attrs);
            }
        });

        searchTextField.registerKeyboardAction((e) -> resultList.setSelectedIndex(Math.max(0, resultList.getSelectedIndex() - 1)), KeyStroke.getKeyStroke("UP"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        searchTextField.registerKeyboardAction((e) -> resultList.setSelectedIndex(Math.min(resultList.getModel().getSize() - 1, resultList.getSelectedIndex() + 1)), KeyStroke.getKeyStroke("DOWN"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }
    SearchEverywherePopup() {
        setLayout(new BorderLayout());
        getRootPane().registerKeyboardAction(e -> setVisible(false), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> openClass(resultList.getSelectedIndex()), KeyStroke.getKeyStroke("ENTER"), JComponent.WHEN_IN_FOCUSED_WINDOW);

        add(this.searchTextField, BorderLayout.NORTH);
        add(this.resultListScrollPane, BorderLayout.CENTER);

        ((JPanel) getContentPane()).setBorder(DynamicMatteBorder.rule(1, 1, 1, 1));

        setUndecorated(true);
        pack();
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                setVisible(false);
            }
        });
    }

    void open() {
        if (isVisible()) {
            toFront();
            return;
        }

        setVisible(true);
        UIUtils.centerJFrame(this);

        this.searchTextField.grabFocus();
        this.searchTextField.selectAll();
    }

    private void openClass(int index) {
        if (index < 0)
            return;

        CompanionApp.SERVER.getMessageProcessor().enqueueMessage(new DecompileOrOpenMessage(resultList.getModel().getElementAt(index).getNameWithPackageDot()));
        setVisible(false);
    }

    private static JLabel primaryLabel(String primary) {
        var primaryLabel = new JLabel(primary);
        primaryLabel.setForeground(ThemeColors.text());
        return primaryLabel;
    }

    private static JLabel secondaryLabel(String secondary) {
        var secondaryLabel = new JLabel(secondary);
        secondaryLabel.setForeground(ThemeColors.mutedText());
        return secondaryLabel;
    }
}
