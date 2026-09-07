package com.github.minecraft_ta.totalDebugCompanion.util;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

@FunctionalInterface
public interface DocumentChangeListener extends DocumentListener {

    void onChanged(DocumentEvent e);

    @Override
    default void insertUpdate(DocumentEvent e) {
        onChanged(e);
    }

    @Override
    default void removeUpdate(DocumentEvent e) {
        onChanged(e);
    }

    @Override
    default void changedUpdate(DocumentEvent e) {
        onChanged(e);
    }
}
