package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeInventory;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Searchable, non-closing module chooser used by Search Everywhere. */
final class ModuleFilterPopup extends JPopupMenu {
    private static final int LIST_WIDTH = 330;
    private static final int MAX_LIST_HEIGHT = 280;
    private static final int ROW_HEIGHT = 24;

    private final FlatIconTextField searchField = new FlatIconTextField(Icons.SEARCH_ICON);
    private final JPanel moduleList = new JPanel();
    private final JScrollPane moduleScroll = new JScrollPane(this.moduleList);
    private final Consumer<Set<String>> selectionListener;

    private List<RuntimeInventory.RuntimeModule> modules;
    private final LinkedHashSet<String> selectedModuleIds;

    ModuleFilterPopup(
            List<RuntimeInventory.RuntimeModule> modules,
            Set<String> selectedModuleIds,
            Consumer<Set<String>> selectionListener
    ) {
        this.modules = List.copyOf(modules);
        this.selectedModuleIds = new LinkedHashSet<>(selectedModuleIds);
        this.selectionListener = selectionListener;

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(this.searchField, BorderLayout.NORTH);

        this.moduleList.setLayout(new BoxLayout(this.moduleList, BoxLayout.Y_AXIS));
        this.moduleScroll.setBorder(BorderFactory.createEmptyBorder());
        this.moduleScroll.setPreferredSize(new Dimension(LIST_WIDTH, ROW_HEIGHT * 3));
        this.moduleScroll.getVerticalScrollBar().setUnitIncrement(18);
        content.add(this.moduleScroll, BorderLayout.CENTER);
        content.add(actionRow(), BorderLayout.SOUTH);
        add(content);

        this.searchField.putClientProperty("JTextField.placeholderText", "Search modules");
        this.searchField.getDocument().addDocumentListener((DocumentChangeListener) event -> rebuildList());
        rebuildList();
    }

    void updateModules(List<RuntimeInventory.RuntimeModule> modules, Set<String> selectedModuleIds) {
        this.modules = List.copyOf(modules);
        this.selectedModuleIds.clear();
        this.selectedModuleIds.addAll(selectedModuleIds);
        rebuildList();
    }

    void focusSearch() {
        this.searchField.requestFocusInWindow();
        this.searchField.selectAll();
    }

    private JPanel actionRow() {
        JButton all = actionButton("All", () -> replaceSelection(this.modules.stream()
                .map(RuntimeInventory.RuntimeModule::id)
                .toList()));
        JButton none = actionButton("None", () -> replaceSelection(List.of()));
        JButton invert = actionButton("Invert", () -> {
            ArrayList<String> inverted = new ArrayList<>();
            for (RuntimeInventory.RuntimeModule module : this.modules) {
                if (!this.selectedModuleIds.contains(module.id())) {
                    inverted.add(module.id());
                }
            }
            replaceSelection(inverted);
        });

        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.add(new JLabel("Select:"));
        actions.add(Box.createHorizontalStrut(4));
        actions.add(all);
        actions.add(none);
        actions.add(invert);
        actions.add(Box.createHorizontalGlue());
        return actions;
    }

    private JButton actionButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setFocusable(false);
        button.addActionListener(event -> action.run());
        return button;
    }

    private void rebuildList() {
        String query = this.searchField.getText().strip().toLowerCase(Locale.ROOT);
        this.moduleList.removeAll();
        int visibleModules = 0;
        for (RuntimeInventory.RuntimeModule module : this.modules) {
            if (!query.isEmpty()
                    && !module.displayName().toLowerCase(Locale.ROOT).contains(query)
                    && !module.id().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            JCheckBox checkBox = new JCheckBox(module.label(), this.selectedModuleIds.contains(module.id()));
            checkBox.setToolTipText(module.id());
            checkBox.setAlignmentX(LEFT_ALIGNMENT);
            checkBox.addActionListener(event -> {
                if (checkBox.isSelected()) {
                    this.selectedModuleIds.add(module.id());
                } else {
                    this.selectedModuleIds.remove(module.id());
                }
                notifySelectionChanged();
            });
            this.moduleList.add(checkBox);
            visibleModules++;
        }
        if (this.moduleList.getComponentCount() == 0) {
            JLabel empty = new JLabel("No matching modules");
            empty.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
            this.moduleList.add(empty);
        }
        int visibleRows = Math.max(1, visibleModules);
        this.moduleScroll.setPreferredSize(new Dimension(
                LIST_WIDTH,
                Math.min(MAX_LIST_HEIGHT, Math.max(48, visibleRows * ROW_HEIGHT + 4))
        ));
        this.moduleList.revalidate();
        this.moduleList.repaint();
    }

    private void replaceSelection(List<String> moduleIds) {
        this.selectedModuleIds.clear();
        this.selectedModuleIds.addAll(moduleIds);
        rebuildList();
        notifySelectionChanged();
    }

    private void notifySelectionChanged() {
        this.selectionListener.accept(Set.copyOf(this.selectedModuleIds));
    }
}
