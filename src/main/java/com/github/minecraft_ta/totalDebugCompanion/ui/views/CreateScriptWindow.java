package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class CreateScriptWindow extends JFrame {

    public CreateScriptWindow(EditorTabs editorTabs) {
        if (!CompanionApp.hasCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION)) {
            throw new IllegalStateException("Script execution was not negotiated for this session");
        }
        var header = new JPanel();
        header.add(new JLabel("New Script"));

        var textField = new FlatIconTextField(new FlatSVGIcon(Icons.JAVA_FILE));
        textField.setPreferredSize(new Dimension(150, (int) textField.getPreferredSize().getHeight()));

        var verifyInput = (Predicate<String>) (s) -> !s.isBlank() && !Files.exists(CompanionApp.getRootPath().resolve("scripts").resolve(s + ".java")) && s.matches("^[^\\d_]\\w*$");
        var setIconAndVerify = (Supplier<Boolean>) () -> {
            var result = verifyInput.test(textField.getText());
            textField.setIconFilter(result ? null : new FlatSVGIcon.ColorFilter((c) -> Color.decode("#D05B64")));
            return result;
        };
        textField.getDocument().addDocumentListener((DocumentChangeListener) e -> setIconAndVerify.get());
        textField.addActionListener((e) -> {
            if (!setIconAndVerify.get())
                return;
            editorTabs.openEditorTab(new ScriptView(textField.getText()));
            dispose();
        });
        textField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() != KeyEvent.VK_ESCAPE)
                    return;
                dispose();
            }
        });
        setIconAndVerify.get();

        getContentPane().setLayout(new BorderLayout());
        ((JPanel) getContentPane()).setBorder(BorderFactory.createMatteBorder(2, 2, 2, 2, Color.GRAY));
        getContentPane().add(header, BorderLayout.NORTH);
        getContentPane().add(textField, BorderLayout.CENTER);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                dispose();
            }

            @Override
            public void windowLostFocus(WindowEvent e) {
                dispose();
            }
        });
        setUndecorated(true);
        pack();
    }
}
