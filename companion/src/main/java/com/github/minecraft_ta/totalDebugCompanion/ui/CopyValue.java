package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.github.minecraft_ta.totalDebugCompanion.Icons;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;

/** A readable value and an explicit copy action with local feedback. */
public final class CopyValue extends JPanel {
    private final JLabel label = PopupElements.label("");
    private final JButton copy = new JButton("Copy", Icons.COPY);
    private final Timer feedback = new Timer(1500, event -> resetFeedback());
    private String value = "";

    public CopyValue(String actionName) {
        super(new BorderLayout(12, 0));
        setOpaque(false);
        copy.setMargin(new Insets(2, 7, 2, 7));
        copy.setToolTipText(actionName);
        copy.getAccessibleContext().setAccessibleName(actionName);
        copy.putClientProperty("JButton.minimumWidth", 0);
        copy.setText("Copied");
        Dimension size = copy.getPreferredSize();
        copy.setPreferredSize(size);
        copy.setText("Copy");
        copy.addActionListener(event -> {
            PopupElements.copy(value);
            copy.setText("Copied");
            copy.setIcon(Icons.SUCCESS);
            feedback.restart();
        });
        feedback.setRepeats(false);
        label.setMinimumSize(new Dimension(0, label.getPreferredSize().height));
        add(label, BorderLayout.CENTER);
        add(copy, BorderLayout.EAST);
        setValue("", "");
    }

    public void setValue(String display, String value) {
        if (!this.value.equals(value)) resetFeedback();
        this.value = value;
        label.setText(display);
        label.setToolTipText(value);
        copy.setEnabled(!value.isEmpty());
        setVisible(!value.isEmpty());
    }

    @Override public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        // Fractional display scales can round the label's measured and painted widths differently.
        size.width += 2;
        return size;
    }

    private void resetFeedback() {
        feedback.stop();
        copy.setText("Copy");
        copy.setIcon(Icons.COPY);
    }

    @Override public void removeNotify() {
        resetFeedback();
        super.removeNotify();
    }
}
