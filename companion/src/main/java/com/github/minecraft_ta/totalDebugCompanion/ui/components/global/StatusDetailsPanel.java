package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import javax.swing.UIManager;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/** Selectable literal status text. Shared presentation only; callers own their controls. */
public final class StatusDetailsPanel extends JPanel {
    private final JTextArea text = new JTextArea();
    public StatusDetailsPanel(String details) {
        super(new BorderLayout(0, 6));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(UIManager.getFont("Label.font"));
        JScrollPane scroll = new JScrollPane(text);
        scroll.setPreferredSize(new Dimension(360, 100));
        add(scroll, BorderLayout.CENTER);
        JButton copy = new JButton("Copy details");
        copy.addActionListener(event -> copy(text.getText()));
        add(copy, BorderLayout.SOUTH);
        setDetails(details);
    }
    public void setDetails(String details) {
        if (text.getText().equals(details)) return;
        text.setText(details);
        text.setCaretPosition(0);
    }
    public static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }
}
