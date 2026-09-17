package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupChrome;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;

public class CreateScriptWindow extends JDialog {

    public CreateScriptWindow(EditorTabs editorTabs, EditorContext context, Runnable onCreated) {
        super(SwingUtilities.getWindowAncestor(editorTabs), "New Script");
        if (context.project() == null) {
            throw new IllegalStateException("Open a Minecraft profile before creating scripts");
        }
        var textField = new JTextField(26);
        textField.setName("scriptName");
        textField.getAccessibleContext().setAccessibleName("Name");
        textField.putClientProperty("JTextField.placeholderText", "Name");
        textField.putClientProperty("JTextField.leadingIcon", Icons.JAVA_FILE);
        textField.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        var error = new JLabel();
        error.setName("scriptNameError");
        error.setForeground(ThemeColors.error());
        error.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));

        BooleanSupplier validate = () -> {
            String name = textField.getText();
            String message = name.isEmpty() ? null
                    : !JavaSnippetSource.isValidClassName(name) ? "Use a valid Java identifier."
                    : Files.exists(context.project().paths().scripts().resolve(name + ScriptView.FILE_EXTENSION))
                    ? "A script with this name already exists." : null;
            error.setText(message);
            error.setVisible(message != null);
            pack();
            return !name.isEmpty() && message == null;
        };
        textField.getDocument().addDocumentListener((DocumentChangeListener) e -> validate.getAsBoolean());
        textField.addActionListener(e -> {
            if (!validate.getAsBoolean()) return;
            editorTabs.openEditorTab(new ScriptView(context, textField.getText()));
            onCreated.run();
            dispose();
        });
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        var title = new JLabel("New Script", SwingConstants.CENTER);
        title.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
        var header = new JPanel(new BorderLayout());
        header.add(title, BorderLayout.NORTH);
        header.add(textField, BorderLayout.CENTER);
        header.setBorder(DynamicMatteBorder.rule(0, 0, 1, 0));

        var types = new JList<>(new String[]{"Script"});
        types.getAccessibleContext().setAccessibleName("Content type");
        types.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        types.setSelectedIndex(0);
        types.setVisibleRowCount(1);
        types.setFocusable(false);
        types.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                setIcon(Icons.JAVA_FILE);
                setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
                return this;
            }
        });
        var body = new JPanel(new BorderLayout(0, 4));
        body.add(header, BorderLayout.NORTH);
        body.add(types, BorderLayout.CENTER);
        body.add(error, BorderLayout.SOUTH);
        body.setBorder(PopupChrome.contentPadding());
        var content = new JPanel(new BorderLayout());
        content.setBorder(PopupChrome.border());
        content.add(body);
        setContentPane(content);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);
        setUndecorated(true);
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent event) {
                dispose();
            }
        });
        validate.getAsBoolean();
    }
}
