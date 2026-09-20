package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import javax.swing.SwingUtilities;
import javax.swing.JCheckBox;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupElements;
import com.github.minecraft_ta.totalDebugCompanion.runtime.IndexIdentity;
import java.nio.file.Path;
import java.util.Locale;
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
    private final JLabel taskDetails = PopupElements.label("");
    private final JLabel taskFailure = PopupElements.label("");
    private final JButton retry;
    private final JCheckBox mcpEnabled = new JCheckBox("Enable MCP server");
    private String mcpAddress = "";
    private final JButton mcpEndpoint = PopupElements.link("", Icons.COPY, () -> PopupElements.copy(mcpAddress));
    private final JLabel mcpDetail = PopupElements.label("");
    private Consumer<Boolean> toggleMcp;
    private final JLabel gameName = PopupElements.label("Minecraft is not connected");
    private Path gamePath;
    private final JButton gameDirectory = PopupElements.link("", Icons.COPY, () -> PopupElements.copy(gamePath.toString()));
    private final JLabel gameProcess = PopupElements.label("");
    private final JPanel gameDirectoryRow = PopupElements.row(PopupElements.label("Game directory"), gameDirectory);
    private final JPanel gameProcessRow = PopupElements.row(PopupElements.label("Process"), gameProcess);
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


    public ApplicationStatusBar(Consumer<NavigationTarget> navigator, Runnable retryIndex, NotificationCenter center,
                                EditorScriptRunService runs, Function<Source, String> unavailable, Consumer<Source> openSource) {
        this.notifications = new NotificationWidget(center, unavailable, openSource);
        this.notifications.setSourceVisible(source -> source.target() != null && source.target().equals(selectedTarget));
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
        retry = PopupElements.icon(Icons.REFRESH, "Rebuild index", retryIndex);
        JPanel indexContent = PopupElements.column();
        indexContent.add(PopupElements.row(taskDetails, retry));
        indexContent.add(taskFailure);
        PopupElements.content(taskPopup, indexContent);
        JPanel mcpContent = PopupElements.column();
        mcpEnabled.setOpaque(false);
        mcpEnabled.setBorder(BorderFactory.createEmptyBorder());
        mcpEnabled.setAlignmentX(LEFT_ALIGNMENT);
        mcpEnabled.addActionListener(event -> {
            if (toggleMcp != null) {
                mcpEnabled.setEnabled(false);
                toggleMcp.accept(mcpEnabled.isSelected());
            }
        });
        mcpContent.add(mcpEnabled);
        mcpContent.add(Box.createVerticalStrut(8));
        mcpContent.add(PopupElements.row(mcpEndpoint, null));
        mcpContent.add(mcpDetail);
        mcpStatus.setContent(mcpContent);
        JPanel gameContent = PopupElements.column();
        gameContent.add(PopupElements.row(gameName, null));
        gameDirectoryRow.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        gameProcessRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        gameContent.add(gameDirectoryRow);
        gameContent.add(gameProcessRow);
        gameDirectoryRow.setVisible(false);
        gameProcessRow.setVisible(false);
        gameStatus.setContent(gameContent);
        add(this.gameStatus);
        add(this.mcpStatus);
        add(this.taskCards);
        applyTheme();
        ThemeManager.addThemeChangeListener(this.themeListener);
        setRuntimeStatus(new RuntimeIndexService.Status(RuntimeIndexService.Phase.WAITING, "Waiting for runtime inventory", null));
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
            taskProgress.setVisible(status.active());
            taskState.setIcon(switch (status.phase()) {
                case READY -> Icons.SUCCESS;
                case FAILED -> Icons.ERROR;
                default -> Icons.INFORMATION;
            });
            taskState.setText(switch (status.phase()) {
                case READY -> status.sourceKind() == IndexIdentity.Kind.LOCAL ? "Local index ready" : "Runtime index ready";
                case FAILED -> "Index failed";
                default -> status.detail();
            });
            taskState.setToolTipText("Show index status");
            var metrics = status.metrics();
            taskDetails.setText(metrics == null ? taskState.getText() : String.format(Locale.getDefault(),
                    "%s %,d classes in %.1f s", metrics.rebuilt() ? "Indexed" : "Loaded", metrics.classes(), metrics.elapsedNanos() / 1_000_000_000.0));
            taskFailure.setVisible(status.phase() == RuntimeIndexService.Phase.FAILED
                    || status.phase() == RuntimeIndexService.Phase.READY && !status.detail().equals(taskState.getText()));
            if (taskFailure.isVisible()) PopupElements.wrappedText(taskFailure, status.failure() == null ? status.detail() : status.failure().toString(), 320);
            retry.setToolTipText(status.phase() == RuntimeIndexService.Phase.FAILED ? "Retry indexing" : "Rebuild index");
            retry.setEnabled(status.phase() == RuntimeIndexService.Phase.FAILED || status.phase() == RuntimeIndexService.Phase.READY);
            if (taskPopup.isVisible()) taskPopup.pack();
        });
    }

    public void refreshContext() { notifications.selectionChanged(); }

    public void setGameStatus(ServiceStatus status) {
        this.gameStatus.setStatus(status);
        if (status.state() != ServiceStatus.State.AVAILABLE) {
            gameName.setText(status.detail());
            gameDirectoryRow.setVisible(false);
            gameProcessRow.setVisible(false);
            gamePath = null;
        }
        gameStatus.refreshPopup();
    }

    public void setGameIdentity(String name, Path directory, long processId) {
        gameName.setText(name);
        gamePath = directory;
        Path parent = directory.getParent();
        gameDirectory.setText(parent == null ? directory.toString() : "…" + directory.getFileSystem().getSeparator() + parent.getFileName() + directory.getFileSystem().getSeparator() + directory.getFileName());
        gameDirectory.setToolTipText("Copy " + directory);
        gameDirectoryRow.setVisible(true);
        gameProcess.setText(Long.toString(processId));
        gameProcessRow.setVisible(processId > 0);
        gameStatus.refreshPopup();
    }

    public void setMcpToggle(Consumer<Boolean> toggle) { toggleMcp = toggle; }

    public void setMcpStatus(ServiceStatus status) {
        this.mcpStatus.setStatus(status);
        mcpEnabled.setEnabled(status.state() != ServiceStatus.State.PENDING && toggleMcp != null);
        if (status.state() != ServiceStatus.State.PENDING) mcpEnabled.setSelected(status.state() == ServiceStatus.State.AVAILABLE);
        mcpAddress = status.state() == ServiceStatus.State.AVAILABLE ? status.detail() : "";
        mcpEndpoint.setText(mcpAddress);
        mcpEndpoint.setVisible(!mcpAddress.isEmpty());
        mcpDetail.setVisible(status.state() == ServiceStatus.State.FAILED || status.state() == ServiceStatus.State.PENDING);
        if (mcpDetail.isVisible()) PopupElements.wrappedText(mcpDetail, status.detail(), 280);
        mcpStatus.refreshPopup();
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
        PopupElements.showAbove(taskPopup, taskState);
    }
}
