package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.function.Consumer;

/**
 * Appearance settings.
 *
 * <p>Deliberately non-modal: every control applies immediately, so the point is to watch the window
 * behind this one change while you pick.
 */
public class SettingsWindow extends JDialog {

    public SettingsWindow(Window owner) {
        super(owner, "Settings", ModalityType.MODELESS);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        addSection(form, row++, "Appearance");
        addRow(form, row++, "Theme", createThemeChooser());
        GlobalConfig config = GlobalConfig.getInstance();
        addRow(form, row++, "Editor font size", createFontSizeSpinner(
                config.editorFontSize(),
                config::setEditorFontSize,
                () -> { }
        ));
        addRow(form, row++, "UI font size", createFontSizeSpinner(
                config.uiFontSize(),
                config::setUiFontSize,
                ThemeManager::reapply
        ));
        addSection(form, row++, "Debugger");
        addWideRow(form, row++, createExceptionBreakpointToggle(
                "Pause on caught exceptions",
                config.breakOnCaughtExceptions(),
                config::setBreakOnCaughtExceptions
        ));
        addWideRow(form, row, createExceptionBreakpointToggle(
                "Pause on uncaught exceptions",
                config.breakOnUncaughtExceptions(),
                config::setBreakOnUncaughtExceptions
        ));

        content.add(form, BorderLayout.CENTER);
        content.add(createButtonBar(), BorderLayout.SOUTH);

        setContentPane(content);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setMinimumSize(getPreferredSize());
        setLocationRelativeTo(owner);
    }

    private JComponent createThemeChooser() {
        JComboBox<CompanionTheme> chooser = new JComboBox<>(CompanionTheme.available().toArray(new CompanionTheme[0]));
        chooser.setSelectedItem(ThemeManager.current());
        chooser.addActionListener(event -> {
            CompanionTheme selected = (CompanionTheme) chooser.getSelectedItem();
            if (selected != null) {
                ThemeManager.apply(selected);
            }
        });
        return chooser;
    }

    private JComponent createFontSizeSpinner(float current, Consumer<Float> setter, Runnable afterChange) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(
                current,
                GlobalConfig.MIN_FONT_SIZE,
                GlobalConfig.MAX_FONT_SIZE,
                1f
        ));
        spinner.addChangeListener(event -> {
            float value = ((Number) spinner.getValue()).floatValue();
            setter.accept(value);
            afterChange.run();
        });
        return spinner;
    }

    private JComponent createExceptionBreakpointToggle(
            String label,
            boolean selected,
            Consumer<Boolean> setter
    ) {
        JCheckBox toggle = new JCheckBox(label, selected);
        toggle.addActionListener(event -> {
            setter.accept(toggle.isSelected());
            GlobalConfig config = GlobalConfig.getInstance();
            CompanionApp.getDebuggerController()
                    .setExceptionBreakpoints(
                            config.breakOnCaughtExceptions(),
                            config.breakOnUncaughtExceptions()
                    );
        });
        return toggle;
    }

    private JComponent createButtonBar() {
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        getRootPane().setDefaultButton(close);

        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.LINE_AXIS));
        bar.add(Box.createHorizontalGlue());
        bar.add(close);
        return bar;
    }

    private static void addRow(JPanel form, int row, String label, Component field) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.LINE_START;
        labelConstraints.insets = new Insets(4, 0, 4, 10);
        form.add(new JLabel(label, SwingConstants.LEADING), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(4, 0, 4, 0);
        field.setPreferredSize(new Dimension(180, field.getPreferredSize().height));
        form.add(field, fieldConstraints);
    }

    private static void addSection(JPanel form, int row, String title) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 2;
        constraints.anchor = GridBagConstraints.LINE_START;
        constraints.insets = new Insets(row == 0 ? 0 : 12, 0, 4, 0);
        JLabel label = new JLabel(title);
        label.putClientProperty("FlatLaf.styleClass", "h4");
        form.add(label, constraints);
    }

    private static void addWideRow(JPanel form, int row, Component field) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(2, 0, 2, 0);
        form.add(field, constraints);
    }
}
