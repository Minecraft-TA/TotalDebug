package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.function.Consumer;

/** A readable value and an explicit copy action with local feedback. */
public final class CopyValue extends JPanel {
    private final JLabel label = PopupElements.label("");
    private final JButton copy = new FlatIconButton(Icons.COPY, false);
    private final String actionName;
    private final Timer feedback = new Timer(1500, event -> resetFeedback());
    private String value = "";

    public CopyValue(String actionName) {
        this(actionName, PopupElements::copy);
    }

    CopyValue(String actionName, Consumer<String> copyAction) {
        super(new BorderLayout(12, 0));
        this.actionName = actionName;
        setOpaque(false);
        copy.setMargin(UiMetrics.compactButtonMargin());
        copy.setToolTipText(actionName);
        copy.getAccessibleContext().setAccessibleName(actionName);
        copy.addActionListener(event -> {
            copyAction.accept(value);
            copy.setToolTipText("Copied");
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
        label.setToolTipText(Tooltip.of("").text(value).html());
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
        copy.setToolTipText(actionName);
        copy.setIcon(Icons.COPY);
    }

    @Override public void removeNotify() {
        resetFeedback();
        super.removeNotify();
    }
}
