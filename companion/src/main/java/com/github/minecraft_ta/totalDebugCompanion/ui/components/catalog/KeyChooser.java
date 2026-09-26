package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A list of keys to pick one from, with a filter, for keys that are hard to press in Companion such as the left mouse
 * button. Enter or a double-click picks the selected key; Escape closes the list.
 */
final class KeyChooser {
    private KeyChooser() {
    }

    /** Shows the list below {@code cell} of {@code owner}; {@code name} gives the name the game shows for a key. */
    static void show(JComponent owner, Rectangle cell, List<String> keys, Function<String, String> name, Consumer<String> chosen) {
        JPopupMenu popup = new JPopupMenu();
        FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
        filter.putClientProperty("JTextField.placeholderText", "Filter keys");
        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((component, key, index, selected, focused) -> {
            PrimarySecondaryLabel label = new PrimarySecondaryLabel();
            label.configure(new PrimarySecondaryText(name.apply(key), key), null, component.getFont(), selected,
                    selected ? component.getSelectionForeground() : ThemeColors.text(),
                    selected ? component.getSelectionBackground() : component.getBackground());
            label.setOpaque(true);
            label.setBackground(selected ? component.getSelectionBackground() : component.getBackground());
            label.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            return label;
        });
        Runnable refill = () -> {
            String query = filter.getText().strip().toLowerCase(Locale.ROOT);
            model.clear();
            for (String key : keys) {
                if (query.isEmpty() || name.apply(key).toLowerCase(Locale.ROOT).contains(query) || key.contains(query)) {
                    model.addElement(key);
                }
            }
            if (!model.isEmpty()) list.setSelectedIndex(0);
        };
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refill.run(); }
            @Override public void removeUpdate(DocumentEvent event) { refill.run(); }
            @Override public void changedUpdate(DocumentEvent event) { refill.run(); }
        });
        Runnable pick = () -> {
            String key = list.getSelectedValue();
            popup.setVisible(false);
            if (key != null) chosen.accept(key);
        };
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) pick.run();
            }
        });
        for (JComponent component : List.of(filter, list)) {
            component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "pickKey");
            component.getActionMap().put("pickKey", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    pick.run();
                }
            });
        }
        // Up and Down in the filter move through the list.
        for (int key : new int[]{KeyEvent.VK_UP, KeyEvent.VK_DOWN}) {
            int step = key == KeyEvent.VK_UP ? -1 : 1;
            filter.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), "moveKey" + key);
            filter.getActionMap().put("moveKey" + key, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    int next = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + step));
                    if (next >= 0 && next < model.size()) {
                        list.setSelectedIndex(next);
                        list.ensureIndexIsVisible(next);
                    }
                }
            });
        }
        refill.run();
        JPanel content = new JPanel(new BorderLayout(0, 4));
        content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        content.add(filter, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(280, 300));
        content.add(scroll, BorderLayout.CENTER);
        popup.add(content);
        popup.show(owner, cell.x, cell.y + cell.height);
        SwingUtilities.invokeLater(filter::requestFocusInWindow);
    }
}
