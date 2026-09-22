package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.ui.PopupChrome;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/** The name-entry interaction shared by script/folder creation, rename and duplication. */
public final class FileNamePopup extends JDialog {
    private boolean submitting;
    public FileNamePopup(Window owner, String title, String kind, Icon icon, String initial,
                         Function<String, String> validate, Function<String, CompletableFuture<?>> submit) {
        super(owner, title);
        var name = new JTextField(initial, 26);
        name.setName("fileName");
        name.getAccessibleContext().setAccessibleName("Name");
        name.putClientProperty("JTextField.placeholderText", "Name");
        name.putClientProperty("JTextField.leadingIcon", icon);
        name.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        var error = new JLabel() {
            @Override public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                size.width = 0; // The name field determines popup width; long errors remain available as a tooltip.
                return size;
            }
        };
        error.setName("fileNameError");
        error.setForeground(ThemeColors.error());
        error.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));
        Runnable check = () -> {
            String problem = name.getText().isEmpty() ? null : validate.apply(name.getText());
            error.setText(problem);
            error.setToolTipText(problem);
            error.setVisible(problem != null);
            pack();
        };
        name.getDocument().addDocumentListener((DocumentChangeListener) event -> check.run());
        name.addActionListener(event -> {
            if (submitting || name.getText().isBlank() || validate.apply(name.getText()) != null) return;
            submitting = true;
            name.setEnabled(false);
            CompletableFuture<?> work;
            try { work = submit.apply(name.getText()); }
            catch (RuntimeException failure) { work = CompletableFuture.failedFuture(failure); }
            work.whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
                submitting = false;
                if (failure == null) { dispose(); return; }
                Throwable cause = failure;
                while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null)
                    cause = cause.getCause();
                error.setText(cause.getMessage());
                error.setToolTipText(cause.getMessage());
                error.setVisible(true);
                name.setEnabled(true);
                pack();
                name.requestFocusInWindow();
            }));
        });
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        // This undecorated popup has no window title bar; the heading is its only visible title.
        var heading = new JLabel(title, SwingConstants.CENTER);
        heading.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
        var header = new JPanel(new BorderLayout());
        header.add(heading, BorderLayout.NORTH);
        header.add(name, BorderLayout.CENTER);
        var body = new JPanel(new BorderLayout(0, 4));
        body.add(header, BorderLayout.NORTH);
        if (kind != null) {
            header.setBorder(DynamicMatteBorder.rule(0, 0, 1, 0));
            var types = new JList<>(new String[]{kind});
            types.setSelectedIndex(0);
            types.setFocusable(false);
            types.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
                    super.getListCellRendererComponent(list, value, index, selected, focused);
                    setIcon(icon);
                    setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
                    return this;
                }
            });
            body.add(types, BorderLayout.CENTER);
        }
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
            @Override public void windowLostFocus(WindowEvent event) { if (!submitting) dispose(); }
        });
        check.run();
        name.selectAll();
    }
}
