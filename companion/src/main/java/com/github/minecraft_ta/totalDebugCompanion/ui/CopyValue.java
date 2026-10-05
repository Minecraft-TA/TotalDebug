package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.function.Consumer;

/** A readable value, or a component showing one, and an explicit copy action with local feedback. */
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
        this(actionName, copyAction, null);
    }

    /** Copy beside {@code shown}, which its owner keeps up to date; {@link #setValue(String)} sets what is copied. */
    public CopyValue(String actionName, JComponent shown) {
        this(actionName, PopupElements::copy, shown);
    }

    private CopyValue(String actionName, Consumer<String> copyAction, JComponent shown) {
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
        add(shown == null ? label : shown, BorderLayout.CENTER);
        add(copy, BorderLayout.EAST);
        setValue("", "");
    }

    public void setValue(String display, String value) {
        label.setText(display);
        label.setToolTipText(Tooltip.of("").text(value).html());
        setValue(value);
    }

    /** What the copy action copies; hidden while it is empty. */
    public void setValue(String value) {
        if (!this.value.equals(value)) resetFeedback();
        this.value = value;
        copy.setEnabled(!value.isEmpty());
        setVisible(!value.isEmpty());
    }

    public String value() {
        return this.value;
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
