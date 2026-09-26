package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A segmented toggle choosing one of a few values, such as views of the same content (docs/UI_GUIDE.md). The first
 * value starts selected; listeners hear only choices made in the control.
 */
public final class SegmentedToggle<T> extends JPanel {
    private final Map<T, JToggleButton> buttons = new LinkedHashMap<>();
    private final List<Consumer<T>> listeners = new ArrayList<>();

    public SegmentedToggle(List<T> values, Function<T, String> label) {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        if (values.isEmpty()) throw new IllegalArgumentException("A segmented toggle needs values");
        ButtonGroup group = new ButtonGroup();
        for (T value : values) {
            JToggleButton button = new JToggleButton(label.apply(value));
            button.putClientProperty("JButton.buttonType", "tab");
            button.addActionListener(event -> this.listeners.forEach(listener -> listener.accept(value)));
            group.add(button);
            this.buttons.put(value, button);
            add(button);
        }
        this.buttons.get(values.getFirst()).setSelected(true);
    }

    public T selected() {
        for (Map.Entry<T, JToggleButton> entry : this.buttons.entrySet()) {
            if (entry.getValue().isSelected()) return entry.getKey();
        }
        throw new IllegalStateException("No segment is selected");
    }

    /** Selects {@code value} without notifying the listeners. */
    public void select(T value) {
        button(value).setSelected(true);
    }

    public void onChange(Consumer<T> listener) {
        this.listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void setEnabled(T value, boolean enabled) {
        button(value).setEnabled(enabled);
    }

    public boolean isEnabled(T value) {
        return button(value).isEnabled();
    }

    public void setToolTipText(T value, String tooltip) {
        button(value).setToolTipText(tooltip);
    }

    private JToggleButton button(T value) {
        JToggleButton button = this.buttons.get(value);
        if (button == null) throw new IllegalArgumentException("Not a segment: " + value);
        return button;
    }
}
