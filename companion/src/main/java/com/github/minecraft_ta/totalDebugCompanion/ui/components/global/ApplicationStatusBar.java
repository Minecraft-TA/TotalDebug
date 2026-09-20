package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import javax.swing.SwingUtilities;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import java.awt.Insets;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.script.EditorScriptRunService;
import java.util.function.Function;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.model.EditorLocation;
import com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel;
import com.github.minecraft_ta.totalDebugCompanion.model.JavaEditorContext;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.navigation.EditorBreadcrumbs;
import com.github.minecraft_ta.totalDebugCompanion.navigation.JavaBreadcrumbResolver;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.Timer;
import java.awt.Dimension;
import java.util.Objects;
import java.util.function.Consumer;

public final class ApplicationStatusBar extends JPanel {
    private final Consumer<CompanionTheme> themeListener = theme -> applyTheme();
    private final BreadcrumbBar breadcrumbs;
    private final JLabel editorStatusLabel = new JLabel();
    private final JButton taskState = new JButton();
    private final JPanel taskCards = new JPanel();
    private final JProgressBar taskProgress = new JProgressBar();
    private final JPopupMenu taskPopup = new JPopupMenu();
    private final StatusDetailsPanel taskDetails = new StatusDetailsPanel("");
    private final JButton retry = new JButton("Retry class indexing");
    private final NotificationWidget notifications;
    private final ScriptActivityWidget scriptActivity;
    private boolean disposed;
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
    private final Timer memberDebounce;

    private Runnable removeMetadataListener = () -> {};
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


    public ApplicationStatusBar(Consumer<NavigationTarget> navigator, Runnable retryIndex, NotificationCenter center,
                                EditorScriptRunService runs, Function<Source, String> unavailable, Consumer<Source> openSource) {
        this.notifications = new NotificationWidget(center, unavailable, openSource);
        this.scriptActivity = new ScriptActivityWidget(runs);
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
        add(this.scriptActivity);
        add(this.notifications);
        add(Box.createHorizontalGlue());

        taskProgress.setIndeterminate(true);
        taskProgress.setStringPainted(false);
        taskProgress.setBorderPainted(false);
        Dimension progressSize = new Dimension(88, 3);
        taskProgress.setMinimumSize(progressSize);
        taskProgress.setPreferredSize(progressSize);
        taskProgress.setMaximumSize(progressSize);
        taskCards.setLayout(new BoxLayout(taskCards, BoxLayout.LINE_AXIS));
        taskCards.setOpaque(false);
        taskCards.setMaximumSize(new Dimension(360, 22));
        FlatIconButton.configure(taskState);
        taskState.setMargin(new Insets(0, 6, 0, 6));
        taskState.putClientProperty("html.disable", true);
        taskState.addActionListener(event -> showTaskPopup());
        taskProgress.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { taskState.doClick(); }
        });
        taskCards.add(taskState);
        taskCards.add(taskProgress);
        taskPopup.add(taskDetails);
        retry.addActionListener(event -> { if (runtimeStatus.phase() == RuntimeIndexService.Phase.FAILED) retryIndex.run(); });
        taskPopup.add(retry);
        add(this.gameStatus);
        add(this.mcpStatus);
        add(this.taskCards);
        applyTheme();
        ThemeManager.addThemeChangeListener(this.themeListener);
        setRuntimeStatus(this.runtimeStatus);
    }

    public void dispose() {
        disposed = true;
        ThemeManager.removeThemeChangeListener(this.themeListener);
        notifications.close();
        scriptActivity.close();
        gameStatus.close();
        mcpStatus.close();
        taskPopup.setVisible(false);
        setEditor(null);
    }

    public void setEditor(IEditorPanel editor) {
        this.memberDebounce.stop();
        this.removeCaretListener.run();
        this.removeAstListener.run();
        this.removeCaretListener = () -> {};
        this.removeAstListener = () -> {};
        this.removeMetadataListener.run();
        this.editorStatusLabel.setText("");
        this.removeMetadataListener = editor == null ? () -> {} : editor.subscribeMetadata(this.editorStatusLabel::setText);
        this.selectedLocation = editor == null ? EditorLocation.empty() : editor.getLocation();
        this.selectedTarget = editor == null ? null : editor.getNavigationTarget();
        this.selectedJavaContext = editor == null ? null : editor.getJavaEditorContext();
        this.selectedMember = null;
        renderBreadcrumbs();
        if (this.selectedJavaContext != null && this.selectedTarget != null) {
            JavaEditorContext context = this.selectedJavaContext;
            this.removeCaretListener = context.addCaretOffsetListener(ignored -> requestMemberRefresh());
            this.removeAstListener = context.astCache().addChangeListener(
                    context.astKey(),
                    snapshot -> requestMemberRefresh()
            );
            requestMemberRefresh();
        }
    }

    private void requestMemberRefresh() {
        UIUtils.onEdt(this.memberDebounce::restart);
    }

    void refreshMember() {
        JavaEditorContext context = this.selectedJavaContext;
        NavigationTarget target = this.selectedTarget;
        JavaBreadcrumbResolver.Member member = null;
        if (context != null && target != null) {
            var snapshot = context.currentSnapshot();
            if (snapshot != null) {
                member = JavaBreadcrumbResolver.resolve(snapshot.unit(), snapshot.sourceMap().toGeneratedOffset(context.caretOffset()), target);
                if (member != null) {
                    int offset = snapshot.sourceMap().toEditorOffset(member.offset());
                    // Generated wrapper members are not editor destinations; real members use editor offsets.
                    member = offset < 0 ? null : new JavaBreadcrumbResolver.Member(member.label(),
                            member.target() instanceof NavigationTarget.LocalFile file
                                    ? new NavigationTarget.LocalFile(file.path(), offset) : member.target(), offset);
                }
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
        UIUtils.onEdt(() -> {
            if (disposed) return;
            runtimeStatus = status;
            taskProgress.setVisible(status.active());
            taskState.setIcon(switch (status.phase()) {
                case READY -> Icons.SUCCESS;
                case FAILED -> Icons.ERROR;
                default -> Icons.INFORMATION;
            });
            taskState.setText(status.detail());
            taskState.setToolTipText("Show index status");
            taskDetails.setDetails(status.sourceKind() + " index: " + status.phase() + "\n" + status.detail()
                    + (status.failure() == null ? "" : "\n" + status.failure()));
            retry.setEnabled(status.phase() == RuntimeIndexService.Phase.FAILED);
        });
    }

    public void refreshContext() { notifications.selectionChanged(); }

    public void setGameStatus(ServiceStatus status) {
        this.gameStatus.setStatus(status);
    }

    public void setMcpStatus(ServiceStatus status) {
        this.mcpStatus.setStatus(status);
    }

    private void applyTheme() {
        notifications.applyTheme();
        scriptActivity.applyTheme();
        gameStatus.applyTheme();
        mcpStatus.applyTheme();
        SwingUtilities.updateComponentTreeUI(taskPopup);
        this.breadcrumbs.applyTheme();
        this.editorStatusLabel.setForeground(ThemeColors.mutedText());
        repaint();
    }

    private void showTaskPopup() {
        taskPopup.show(taskState, Math.min(0, taskState.getWidth() - taskPopup.getPreferredSize().width),
                -taskPopup.getPreferredSize().height);
    }
}
