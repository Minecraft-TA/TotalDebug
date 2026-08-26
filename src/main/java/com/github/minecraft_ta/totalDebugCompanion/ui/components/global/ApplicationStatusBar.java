package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.AnimatedFlatSVGIcon;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;
import java.util.function.Consumer;

public final class ApplicationStatusBar extends JPanel {
    private final JLabel pathLabel = new JLabel();
    private final JLabel editorStatusLabel = new JLabel();
    private final JLabel taskLabel = new JLabel();
    private final JProgressBar taskProgress = new JProgressBar();
    private final JButton taskState = new JButton();
    private final JPanel taskCards = new JPanel(new java.awt.CardLayout());
    private final AnimatedFlatSVGIcon processIcon = new AnimatedFlatSVGIcon("icons/process");
    private final Consumer<BottomInformationBar.State> editorStatusListener = this::setEditorStatus;
    private final Consumer<CompanionTheme> themeListener = theme -> applyTheme();

    private BottomInformationBar selectedEditorStatus;
    private RuntimeIndexService.Status runtimeStatus = new RuntimeIndexService.Status(
            RuntimeIndexService.Phase.WAITING,
            "Waiting for runtime inventory",
            null
    );

    public ApplicationStatusBar() {
        setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));
        setBorder(BorderFactory.createEmptyBorder(2, 7, 0, 5));
        setMinimumSize(new Dimension(0, UiMetrics.STATUS_BAR_HEIGHT));
        setPreferredSize(new Dimension(0, UiMetrics.STATUS_BAR_HEIGHT));

        add(this.pathLabel);
        add(Box.createHorizontalStrut(12));
        add(this.editorStatusLabel);
        add(Box.createHorizontalGlue());

        this.taskProgress.setIndeterminate(true);
        this.taskProgress.setStringPainted(false);
        this.taskProgress.setBorderPainted(false);
        Dimension progressSize = new Dimension(88, 3);
        this.taskProgress.setMinimumSize(progressSize);
        this.taskProgress.setPreferredSize(progressSize);
        this.taskProgress.setMaximumSize(progressSize);
        this.taskProgress.setAlignmentY(Component.CENTER_ALIGNMENT);
        this.taskLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        JPanel activity = new JPanel();
        activity.setOpaque(false);
        activity.setLayout(new BoxLayout(activity, BoxLayout.LINE_AXIS));
        activity.add(this.taskLabel);
        activity.add(Box.createHorizontalStrut(8));
        activity.add(this.taskProgress);
        activity.setAlignmentY(Component.CENTER_ALIGNMENT);
        this.taskCards.add(activity, "progress");
        this.taskCards.setOpaque(false);
        this.taskCards.setMaximumSize(new Dimension(360, 19));

        this.taskState.setBorderPainted(false);
        this.taskState.setContentAreaFilled(false);
        this.taskState.setFocusable(false);
        this.taskState.setHorizontalAlignment(JButton.RIGHT);
        this.taskState.addActionListener(event -> showTaskPopup());
        this.taskCards.add(this.taskState, "state");
        add(this.taskCards);
        applyTheme();
        ThemeManager.addThemeChangeListener(this.themeListener);
        setRuntimeStatus(this.runtimeStatus);
    }

    public void setEditor(IEditorPanel editor) {
        if (this.selectedEditorStatus != null) {
            this.selectedEditorStatus.removeListener(this.editorStatusListener);
        }
        this.selectedEditorStatus = editor == null ? null : editor.getInformationBar();
        var location = editor == null ? com.github.minecraft_ta.totalDebugCompanion.model.EditorLocation.empty()
                : editor.getLocation();
        this.pathLabel.setText(location.breadcrumb());
        this.pathLabel.setToolTipText(location.tooltip().isBlank() ? null : location.tooltip());
        if (this.selectedEditorStatus == null) {
            setEditorStatus(new BottomInformationBar.State("", BottomInformationBar.Style.PLAIN));
        } else {
            this.selectedEditorStatus.addListener(this.editorStatusListener);
        }
    }

    public void setRuntimeStatus(RuntimeIndexService.Status status) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setRuntimeStatus(status));
            return;
        }
        this.runtimeStatus = status;
        java.awt.CardLayout cards = (java.awt.CardLayout) this.taskCards.getLayout();
        if (status.active()) {
            this.taskLabel.setText(status.detail());
            this.taskLabel.setToolTipText(status.detail());
            cards.show(this.taskCards, "progress");
            return;
        }
        this.taskState.setIcon(switch (status.phase()) {
            case READY -> Icons.SUCCESS;
            case FAILED -> Icons.ERROR;
            default -> Icons.INFORMATION;
        });
        this.taskState.setText(status.detail());
        this.taskState.setToolTipText("Show background activity");
        cards.show(this.taskCards, "state");
    }

    private void setEditorStatus(BottomInformationBar.State state) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setEditorStatus(state));
            return;
        }
        this.editorStatusLabel.setText(state.text());
        this.editorStatusLabel.setForeground(
                state.style() == BottomInformationBar.Style.PLAIN ? ThemeColors.mutedText() : getForeground()
        );
        if (state.style() != BottomInformationBar.Style.PROCESS) {
            this.processIcon.stop();
        }
        this.editorStatusLabel.setIcon(switch (state.style()) {
            case INFORMATION -> Icons.INFORMATION;
            case PROCESS -> this.processIcon;
            case SUCCESS -> Icons.SUCCESS;
            case FAILURE -> Icons.ERROR;
            case PLAIN -> null;
        });
    }

    private void applyTheme() {
        this.pathLabel.setForeground(ThemeColors.mutedText());
        this.taskLabel.setForeground(ThemeColors.mutedText());
        setEditorStatus(this.selectedEditorStatus == null
                ? new BottomInformationBar.State("", BottomInformationBar.Style.PLAIN)
                : this.selectedEditorStatus.state());
        repaint();
    }

    private void showTaskPopup() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem summary = new JMenuItem(this.runtimeStatus.detail());
        summary.setEnabled(false);
        popup.add(summary);
        if (this.runtimeStatus.failure() != null) {
            String detail = this.runtimeStatus.failure().toString();
            JMenuItem failure = new JMenuItem(detail);
            failure.setEnabled(false);
            popup.add(failure);
        }
        if (this.runtimeStatus.phase() == RuntimeIndexService.Phase.FAILED) {
            popup.addSeparator();
            JMenuItem retry = new JMenuItem("Retry class indexing");
            retry.addActionListener(event -> CompanionApp.retryRuntimeIndex());
            popup.add(retry);
        }
        popup.show(this.taskState, Math.max(0, this.taskState.getWidth() - popup.getPreferredSize().width), 0);
    }

}
