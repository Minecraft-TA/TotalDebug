package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.search.SearchManager;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;

import javax.swing.*;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import java.awt.*;
import java.util.function.Consumer;

public class SearchHeaderBar extends JPanel {

    public SearchHeaderBar(SearchManager searchManager) {
        super();
        setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));

        var textField = new FlatIconTextField(Icons.SEARCH_ICON);
        textField.setPreferredSize(new Dimension(250, (int) textField.getPreferredSize().getHeight()));
        textField.setMaximumSize(textField.getPreferredSize());
        textField.getDocument().addDocumentListener((DocumentChangeListener) e -> {
            searchManager.setQuery(textField.getText());
        });
        textField.addActionListener(e -> searchManager.focusNextMatch());
        add(textField);
        add(createSeparator());

        add(createToggleableFlatButton("Match case", Icons.MATCH_CASE, (b) -> {
            searchManager.setMatchCase(b);
            //Force refresh
            searchManager.setQuery(textField.getText());
        }));
        JButton regexButton = createToggleableFlatButton("Regex", Icons.REGEX, (b) -> {
            searchManager.setUseRegex(b);
            searchManager.setQuery(textField.getText());
        });
        add(regexButton);
        add(createSeparator());
        add(createFlatButton("Previous", Icons.PREVIOUS_OCCURRENCE, searchManager::focusPreviousMatch));
        add(createFlatButton("Next", Icons.NEXT_OCCURRENCE, searchManager::focusNextMatch));

        JLabel indexLabel = new JLabel("");
        searchManager.addFocusedIndexChangedListener(i -> {
            if (searchManager.getMatchCount() == 0) {
                indexLabel.setText("");
                return;
            }
            indexLabel.setText((i + 1) + "/" + searchManager.getMatchCount());
        });
        add(indexLabel);
        add(Box.createHorizontalGlue());

        setMaximumSize(new Dimension(10000, (int) getPreferredSize().getHeight()));
        setBorder(DynamicMatteBorder.rule(0, 0, 1, 0));

        SwingUtilities.invokeLater(textField::requestFocus);
    }

    private JSeparator createSeparator() {
        JSeparator separator = new JSeparator(JSeparator.VERTICAL);
        separator.setForeground(ThemeColors.border());
        separator.setMaximumSize(new Dimension(1, 100));
        return separator;
    }

    private JButton createToggleableFlatButton(String tooltip, FlatSVGIcon icon, Consumer<Boolean> toggleListener) {
        var button = new FlatIconButton(icon, true);
        button.setToolTipText(tooltip);
        button.addToggleListener(toggleListener);
        return button;
    }

    private JButton createFlatButton(String tooltip, FlatSVGIcon icon, Runnable clickListener) {
        var button = new FlatIconButton(icon, false);
        button.setToolTipText(tooltip);
        button.addActionListener((e) -> clickListener.run());
        return button;
    }
}
