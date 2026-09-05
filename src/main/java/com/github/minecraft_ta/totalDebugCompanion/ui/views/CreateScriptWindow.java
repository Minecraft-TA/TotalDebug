package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
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
        if (!CompanionApp.hasProfile()) {
            throw new IllegalStateException("Open a Minecraft profile before creating scripts");
        }
        var header = new JPanel();
        header.add(new JLabel("New Script"));

        var textField = new FlatIconTextField(new FlatSVGIcon(Icons.JAVA_FILE));
        textField.setPreferredSize(new Dimension(150, (int) textField.getPreferredSize().getHeight()));

        var verifyInput = (Predicate<String>) (s) -> JavaSnippetSource.isValidClassName(s)
                && !Files.exists(CompanionApp.instancePaths().scripts()
                .resolve(s + ScriptView.FILE_EXTENSION));
        var setIconAndVerify = (Supplier<Boolean>) () -> {
            var result = verifyInput.test(textField.getText());
            textField.setIconFilter(result ? null : new FlatSVGIcon.ColorFilter((c) -> ThemeColors.error()));
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
        ((JPanel) getContentPane()).setBorder(new DynamicMatteBorder(2, 2, 2, 2, ThemeColors::border));
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
