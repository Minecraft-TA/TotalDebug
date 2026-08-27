package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.ExpressionCompletionSupport;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupChrome;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.util.Objects;
import java.util.List;

/** Compact editor for the breakpoint at one source line. */
final class BreakpointEditorPopup {
    interface Handler {
        void save(int displayedLine, String condition, String hitCount);

        void remove(int displayedLine);
    }

    private final JPopupMenu popup = new JPopupMenu();
    private final JLabel title = new JLabel();
    private final JTextField condition = new JTextField(28);
    private final ExpressionCompletionSupport conditionCompletion = new ExpressionCompletionSupport(this.condition);
    private final JTextField hitCount = new JTextField(10);
    private final JLabel validation = new JLabel(" ");
    private final JButton remove = new JButton("Remove");
    private final JButton done = new JButton("Done");
    private int displayedLine;
    private Handler handler;

    BreakpointEditorPopup() {
        configureUi();
    }

    void open(
            Component invoker,
            Point location,
            int displayedLine,
            DebugEngine.SourceBreakpoint breakpoint,
            boolean installed,
            ExpressionCompletionSupport.CompletionProvider completionProvider,
            Handler handler
    ) {
        if (displayedLine < 1) {
            throw new IllegalArgumentException("Breakpoint line must be positive");
        }
        this.displayedLine = displayedLine;
        this.handler = Objects.requireNonNull(handler, "handler");
        this.title.setText(breakpoint != null && breakpoint.isMethodEntry()
                ? "Method breakpoint · " + breakpoint.method().name()
                : "Line breakpoint · " + displayedLine);
        this.condition.setText(breakpoint == null || breakpoint.condition() == null
                ? ""
                : breakpoint.condition());
        this.hitCount.setText(breakpoint == null || breakpoint.hitCondition() == null
                ? ""
                : breakpoint.hitCondition());
        this.remove.setVisible(installed);
        this.conditionCompletion.setCompletionProvider(completionProvider);
        clearValidation();
        this.popup.show(invoker, location.x, location.y);
        SwingUtilities.invokeLater(() -> {
            this.condition.requestFocusInWindow();
            this.condition.selectAll();
        });
    }

    void dispose() {
        this.popup.setVisible(false);
        this.conditionCompletion.close();
    }

    private void configureUi() {
        this.popup.setBorder(PopupChrome.border());
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        this.title.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
        content.add(this.title, BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.LINE_START;
        constraints.insets = new Insets(0, 0, 5, 10);
        fields.add(new JLabel("Condition:"), constraints);

        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, 5, 0);
        this.condition.putClientProperty("JTextField.placeholderText", "Java expression");
        fields.add(this.condition, constraints);

        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        constraints.insets = new Insets(0, 0, 0, 10);
        fields.add(new JLabel("Hit count:"), constraints);

        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, 0, 0);
        this.hitCount.putClientProperty("JTextField.placeholderText", "Optional positive integer");
        fields.add(this.hitCount, constraints);
        content.add(fields, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(10, 0));
        this.validation.setPreferredSize(new Dimension(250, this.validation.getPreferredSize().height));
        footer.add(this.validation, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.TRAILING, 6, 0));
        buttons.add(this.remove);
        buttons.add(this.done);
        footer.add(buttons, BorderLayout.EAST);
        content.add(footer, BorderLayout.SOUTH);

        this.done.addActionListener(event -> save());
        this.condition.addActionListener(event -> save());
        this.hitCount.addActionListener(event -> save());
        this.remove.addActionListener(event -> {
            this.popup.setVisible(false);
            this.handler.remove(this.displayedLine);
        });
        this.popup.add(content);
    }

    private void save() {
        String hitCountText = this.hitCount.getText().trim();
        if (!hitCountText.isEmpty()) {
            try {
                if (Integer.parseInt(hitCountText) < 1) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException exception) {
                showValidation("Hit count must be a positive integer");
                return;
            }
        }
        clearValidation();
        this.popup.setVisible(false);
        this.handler.save(this.displayedLine, this.condition.getText(), hitCountText);
    }

    private void showValidation(String message) {
        this.validation.setText(message);
        this.validation.putClientProperty("FlatLaf.styleClass", "error");
        this.hitCount.putClientProperty("JComponent.outline", "error");
        this.hitCount.requestFocusInWindow();
        this.hitCount.selectAll();
    }

    private void clearValidation() {
        this.validation.setText(" ");
        this.validation.putClientProperty("FlatLaf.styleClass", null);
        this.hitCount.putClientProperty("JComponent.outline", null);
    }
}
