package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.model.EditorLocation;
import com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel;
import com.github.minecraft_ta.totalDebugCompanion.model.JavaEditorContext;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.navigation.EditorBreadcrumbs;
import com.github.minecraft_ta.totalDebugCompanion.navigation.JavaBreadcrumbResolver;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
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
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Dimension;
import java.util.Objects;
import java.util.function.Consumer;

public final class ApplicationStatusBar extends JPanel {
    private final BreadcrumbBar breadcrumbs;
    private final JLabel editorStatusLabel = new JLabel();
    private final JLabel taskLabel = new JLabel();
    private final JProgressBar taskProgress = new JProgressBar();
    private final JButton taskState = new JButton();
    private final JPanel taskCards = new JPanel(new java.awt.CardLayout());
    private final ServiceStatusWidget gameStatus = new ServiceStatusWidget(
            "Game",
            new ServiceStatus(
                    ServiceStatus.State.INACTIVE,
                    "Offline",
                    "Minecraft is not connected."
            )
    );
    private final ServiceStatusWidget mcpStatus = new ServiceStatusWidget(
            "MCP",
            new ServiceStatus(
                    ServiceStatus.State.INACTIVE,
                    "Stopped",
                    "The MCP server has not started."
            )
    );
    private final AnimatedFlatSVGIcon processIcon = new AnimatedFlatSVGIcon("icons/process");
    private final Consumer<BottomInformationBar.State> editorStatusListener = this::setEditorStatus;
    private final Consumer<CompanionTheme> themeListener = theme -> applyTheme();
    private final Timer memberDebounce;

    private BottomInformationBar selectedEditorStatus;
    private EditorLocation selectedLocation = EditorLocation.empty();
    private NavigationTarget selectedTarget;
    private JavaEditorContext selectedJavaContext;
    private JavaBreadcrumbResolver.Member selectedMember;
    private Runnable removeCaretListener = () -> {};
    private Runnable removeAstListener = () -> {};
    private RuntimeIndexService.Status runtimeStatus = new RuntimeIndexService.Status(
            RuntimeIndexService.Phase.WAITING,
            "Waiting for runtime inventory",
            null
    );

    public ApplicationStatusBar(Consumer<NavigationTarget> navigator) {
        this.breadcrumbs = new BreadcrumbBar(Objects.requireNonNull(navigator, "navigator"));
        this.memberDebounce = new Timer(140, event -> refreshMember());
        this.memberDebounce.setRepeats(false);
        setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));
        setBorder(BorderFactory.createEmptyBorder(1, 7, 1, 5));
        setMinimumSize(new Dimension(0, UiMetrics.STATUS_BAR_HEIGHT));
        setPreferredSize(new Dimension(0, UiMetrics.STATUS_BAR_HEIGHT));

        add(this.breadcrumbs);
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
        add(this.gameStatus);
        add(this.mcpStatus);
        add(this.taskCards);
        applyTheme();
        ThemeManager.addThemeChangeListener(this.themeListener);
        setRuntimeStatus(this.runtimeStatus);
    }

    public void setEditor(IEditorPanel editor) {
        this.memberDebounce.stop();
        this.removeCaretListener.run();
        this.removeAstListener.run();
        this.removeCaretListener = () -> {};
        this.removeAstListener = () -> {};
        if (this.selectedEditorStatus != null) {
            this.selectedEditorStatus.removeListener(this.editorStatusListener);
        }
        this.selectedEditorStatus = editor == null ? null : editor.getInformationBar();
        this.selectedLocation = editor == null ? EditorLocation.empty() : editor.getLocation();
        this.selectedTarget = editor == null ? null : editor.getNavigationTarget();
        this.selectedJavaContext = editor == null ? null : editor.getJavaEditorContext();
        this.selectedMember = null;
        renderBreadcrumbs();
        if (this.selectedJavaContext != null && this.selectedTarget != null) {
            JavaEditorContext context = this.selectedJavaContext;
            this.removeCaretListener = context.addCaretOffsetListener(ignored -> requestMemberRefresh());
            this.removeAstListener = ASTCache.addChangeListener(
                    context.astKey(),
                    (unit, version) -> requestMemberRefresh()
            );
            requestMemberRefresh();
        }
        if (this.selectedEditorStatus == null) {
            setEditorStatus(new BottomInformationBar.State("", BottomInformationBar.Style.PLAIN));
        } else {
            this.selectedEditorStatus.addListener(this.editorStatusListener);
        }
    }

    private void requestMemberRefresh() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::requestMemberRefresh);
            return;
        }
        this.memberDebounce.restart();
    }

    private void refreshMember() {
        JavaEditorContext context = this.selectedJavaContext;
        NavigationTarget target = this.selectedTarget;
        JavaBreadcrumbResolver.Member member = null;
        if (context != null && target != null) {
            var unit = ASTCache.getFromCache(context.astKey());
            if (unit != null) {
                member = JavaBreadcrumbResolver.resolve(unit, context.caretOffset(), target);
            }
        }
        if (!Objects.equals(this.selectedMember, member)) {
            this.selectedMember = member;
            renderBreadcrumbs();
        }
    }

    private void renderBreadcrumbs() {
        this.breadcrumbs.setSegments(EditorBreadcrumbs.create(
                this.selectedLocation,
                this.selectedTarget,
                this.selectedMember
        ));
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

    public void setGameStatus(ServiceStatus status) {
        this.gameStatus.setStatus(status);
    }

    public void setMcpStatus(ServiceStatus status) {
        this.mcpStatus.setStatus(status);
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
        this.breadcrumbs.applyTheme();
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
        popup.show(
                this.taskState,
                Math.min(0, this.taskState.getWidth() - popup.getPreferredSize().width),
                -popup.getPreferredSize().height
        );
    }

}
