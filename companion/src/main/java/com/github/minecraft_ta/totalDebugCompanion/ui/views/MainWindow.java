package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RetryRuntimeInventoryMessage;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import java.util.function.Supplier;
import java.util.function.Consumer;
import com.github.minecraft_ta.totalDebugCompanion.ui.CompanionUi;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.ApplicationStatusBar;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.BreakpointsWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerActions;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerShortcuts;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerPanel.FrameNavigation;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.WorkspacePanel;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeView;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeViewHeader;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class MainWindow extends JFrame implements AWTEventListener, CompanionUi {


    private final EditorTabs editorTabs = new EditorTabs();
    private final FileTreeView fileTreeView;
    private final NavigationService navigationService;
    private final JMenu scriptMenu = new JMenu("Script");
    private final JButton debuggerState = new JButton("Debugger: Unavailable", Icons.DEBUG);
    private final ApplicationStatusBar statusBar;
    private final Action evaluateExpressionAction;
    private final Action newScriptAction;
    private final DebuggerActions debuggerActions;
    private final DebuggerShortcuts debuggerShortcuts;
    private DebuggerWindow debuggerWindow;
    private BreakpointsWindow breakpointsWindow;
    private EvaluateExpressionWindow evaluateExpressionWindow;
    private SnippetExecutionService snippetExecutions;
    private final DebuggerSessionController.Listener debuggerListener;

    private long lastShiftReleasedTime = 0;
    private SearchEverywherePopup searchEverywherePopup;
    private boolean disposed;
    private final Consumer<CompanionTheme> themeListener = this::updateWindowIcon;
    private final Supplier<ProjectScope> project;
    private final DebuggerSessionController debugger;
    private final CodeInsightService insights;
    private final ScriptExecutionService scripts;
    private final CompanionSession session;
    private final RuntimeIndexService indexLoader;
    private final FrameNavigation frameNavigation;

    public MainWindow(Supplier<ProjectScope> project, DebuggerSessionController debugger, CodeInsightService insights,
                      ScriptExecutionService scripts, CompanionSession session, RuntimeIndexService indexLoader, FrameNavigation frameNavigation, Runnable exit) {
        this.project = project;
        this.debugger = debugger;
        this.insights = insights;
        this.scripts = scripts;
        this.session = session;
        this.indexLoader = indexLoader;
        this.frameNavigation = frameNavigation;
        setAutoRequestFocus(false);

        this.fileTreeView = new FileTreeView(project, target -> navigation().navigate(target));
        this.navigationService = new NavigationService(this, this.editorTabs, this.fileTreeView, project.get(), this::editorContext);
        this.statusBar = new ApplicationStatusBar(target -> this.navigationService.navigate(target), () -> {
            indexLoader.waiting("Requesting runtime inventory again");
            session.send(new RetryRuntimeInventoryMessage());
        });
        getContentPane().add(new WorkspacePanel(
                new FileTreeViewHeader(),
                this.fileTreeView,
                this.editorTabs,
                this.statusBar
        ), BorderLayout.CENTER);
        this.editorTabs.addSelectedEditorListener(this.statusBar::setEditor);

        var menuBar = new JMenuBar();
        menuBar.setBorder(BorderFactory.createEmptyBorder());

        var fileMenu = new JMenu("File");
        fileMenu.add(new AbstractAction("Settings...", Icons.SETTINGS) {
            @Override
            public void actionPerformed(ActionEvent e) {
                new SettingsWindow(MainWindow.this, project.get() == null ? InstanceState.inMemory() : project.get().state(), debugger).setVisible(true);
            }
        });
        menuBar.add(fileMenu);

        this.evaluateExpressionAction = new AbstractAction("Evaluate Expression...", Icons.EVALUATE_EXPRESSION) {
            @Override
            public void actionPerformed(ActionEvent event) {
                evaluateExpressionWindow().showWindow();
            }
        };
        JMenuItem evaluateExpression = new JMenuItem(this.evaluateExpressionAction);
        evaluateExpression.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F8, InputEvent.ALT_DOWN_MASK));
        this.scriptMenu.add(evaluateExpression);
        this.scriptMenu.addSeparator();

        this.newScriptAction = new AbstractAction("New Script", Icons.JAVA_FILE) {
            @Override
            public void actionPerformed(ActionEvent e) {
                var window = new CreateScriptWindow(editorTabs, editorContext());
                window.setVisible(true);
                window.setLocationRelativeTo(MainWindow.this);
            }
        };
        this.scriptMenu.add(this.newScriptAction);
        menuBar.add(this.scriptMenu);
        menuBar.add(Box.createHorizontalGlue());
        this.debuggerActions = new DebuggerActions(debugger);
        this.debuggerShortcuts = new DebuggerShortcuts(this.debuggerActions);
        this.debuggerShortcuts.install(this);
        this.debuggerListener = new DebuggerSessionController.Listener() {
            @Override
            public void statusChanged(DebuggerSessionController.Status status) {
                ProjectScope selected = project.get();
                SwingUtilities.invokeLater(() -> {
                    if (disposed || selected != project.get() || !status.equals(debugger.status())) return;
                    setDebuggerState(status);
                    if (selected != null && selected.isActive() && status.phase() == DebuggerSessionController.Phase.PAUSED) {
                        debuggerWindow(debugger);
                    }
                });
            }
        };
        debugger.addListener(this.debuggerListener);
        configureDebuggerButton(debugger);
        menuBar.add(this.debuggerState);

        setJMenuBar(menuBar);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                exit.run();
            }
        });
        setTitle("TotalDebug Companion");
        updateWindowIcon(ThemeManager.current());
        ThemeManager.addThemeChangeListener(this.themeListener);
        refreshProfile();

        Toolkit.getDefaultToolkit().addAWTEventListener(
                this,
                AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK
        );
    }

    @Override public void dispose() {
        if (!disposed) {
            disposed = true;
            debugger.removeListener(debuggerListener);
            debuggerActions.close();
            debuggerShortcuts.close();
            ThemeManager.removeThemeChangeListener(themeListener);
            Toolkit.getDefaultToolkit().removeAWTEventListener(this);
            closeProjectWindows();
            editorTabs.closeMatching(editor -> true);
            statusBar.dispose();
            editorTabs.astCache().clear();
        }
        super.dispose();
    }

    @Override public boolean canExit() { return editorTabs.canCloseAll(); }
    @Override public void setSwitching(boolean switching) { setEnabled(!switching); }
    @Override public void runtimeChanged() { navigationService.runtimeChanged(); refreshRuntimeSources(); }
    @Override public void navigate(NavigationTarget target, NavigationService.Activation activation) { navigation().navigate(target, activation); }
    @Override public void focus() { UIUtils.focusWindow(this); }
    @Override public void showError(String title, String message) { JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE); }

    public EditorContext editorContext() {
        return new EditorContext(editorTabs.astCache(), this, project.get(), insights, debugger, navigation(), scripts, session, this::showDebuggerValue);
    }

    private void updateWindowIcon(CompanionTheme theme) {
        setIconImages(Icons.createWindowIconImages(theme));
    }

    private void configureDebuggerButton(DebuggerSessionController debugger) {
        this.debuggerState.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        this.debuggerState.setContentAreaFilled(false);
        this.debuggerState.setFocusable(false);
        this.debuggerState.addActionListener(event -> showDebuggerMenu(debugger));
    }

    private void showDebuggerMenu(DebuggerSessionController debugger) {
        DebuggerSessionController.Status status = debugger.status();
        JPopupMenu popup = new JPopupMenu();
        JMenuItem heading = new JMenuItem(status.target() == null
                ? "Minecraft debugger"
                : status.target().displayName());
        heading.setEnabled(false);
        popup.add(heading);
        JMenuItem detail = new JMenuItem(status.detail());
        detail.setEnabled(false);
        popup.add(detail);
        popup.addSeparator();

        popup.add(new AbstractAction("View Breakpoints", Icons.VIEW_BREAKPOINTS) {
            @Override
            public void actionPerformed(ActionEvent event) {
                breakpointsWindow(debugger).showWindow();
            }
        });
        JCheckBoxMenuItem mute = new JCheckBoxMenuItem("Mute Breakpoints", debugger.breakpointsMuted());
        mute.setIcon(Icons.MUTE_BREAKPOINTS);
        mute.addActionListener(event -> debugger.setBreakpointsMuted(mute.isSelected()));
        popup.add(mute);
        popup.addSeparator();

        switch (status.phase()) {
            case DETACHED, FAILED -> popup.add(this.debuggerActions.attach());
            case RUNNING, ATTACHING, DETACHING -> popup.add(this.debuggerActions.detach());
            case PAUSED -> {
                popup.add(new AbstractAction("Show Debugger", Icons.DEBUG) {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        debuggerWindow(debugger).showWindow();
                    }
                });
                popup.add(this.debuggerActions.resume());
                popup.add(this.debuggerActions.stepOver());
                popup.add(this.debuggerActions.stepInto());
                popup.add(this.debuggerActions.stepOut());
                popup.addSeparator();
                popup.add(this.debuggerActions.detach());
            }
            case UNAVAILABLE -> {
            }
        }
        popup.show(
                this.debuggerState,
                Math.max(0, this.debuggerState.getWidth() - popup.getPreferredSize().width),
                this.debuggerState.getHeight()
        );
    }

    private void setDebuggerState(DebuggerSessionController.Status status) {
        String label = switch (status.phase()) {
            case UNAVAILABLE -> "Unavailable";
            case DETACHED -> "Detached";
            case ATTACHING -> "Attaching";
            case RUNNING -> "Running";
            case PAUSED -> "Paused";
            case DETACHING -> "Detaching";
            case FAILED -> "Failed";
        };
        this.debuggerState.setText("Debugger: " + label);
        this.debuggerState.setToolTipText(status.detail());
    }

    private DebuggerWindow debuggerWindow(DebuggerSessionController debugger) {
        if (this.debuggerWindow == null) {
            this.debuggerWindow = new DebuggerWindow(
                    project.get().state(),
                    this,
                    debugger,
                    this.debuggerActions,
                    this.debuggerShortcuts,
                    this.frameNavigation,
                    () -> breakpointsWindow(debugger).showWindow(),
                    target -> this.navigationService.navigate(target)
            );
        }
        return this.debuggerWindow;
    }

    private BreakpointsWindow breakpointsWindow(DebuggerSessionController debugger) {
        if (this.breakpointsWindow == null) {
            this.breakpointsWindow = new BreakpointsWindow(
                    editorTabs.astCache(), this,
                    debugger,
                    target -> this.navigationService.navigate(target)
            );
        }
        return this.breakpointsWindow;
    }

    private EvaluateExpressionWindow evaluateExpressionWindow() {
        if (this.snippetExecutions == null) {
            this.snippetExecutions = new SnippetExecutionService(session, scripts, project.get());
        }
        if (this.evaluateExpressionWindow == null) {
            this.evaluateExpressionWindow = new EvaluateExpressionWindow(this, this.snippetExecutions, editorContext(), this::refreshRuntimeSources);
        }
        return this.evaluateExpressionWindow;
    }

    public void showDebuggerValue(DebugEngine.StackFrame frame, DebugEngine.Variable variable) {
        SwingUtilities.invokeLater(() ->
                debuggerWindow(debugger).showVariable(frame, variable));
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        if (event instanceof MouseEvent mouseEvent) {
            handleHistoryMouseButton(mouseEvent);
            return;
        }
        if (!(event instanceof KeyEvent keyEvent)) {
            return;
        }

        if (handleHistoryKey(keyEvent)) {
            return;
        }

        if (keyEvent.getID() != KeyEvent.KEY_RELEASED || keyEvent.getKeyCode() != KeyEvent.VK_SHIFT)
            return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - this.lastShiftReleasedTime > 300) {
            this.lastShiftReleasedTime = currentTime;
            return;
        }

        this.lastShiftReleasedTime = 0;
        if (project.get() == null) {
            return;
        }
        openSearchEverywhere();
    }

    private boolean handleHistoryKey(KeyEvent event) {
        if (event.getID() != KeyEvent.KEY_PRESSED
                || !event.isControlDown()
                || !event.isAltDown()
                || event.isShiftDown()) {
            return false;
        }
        Action action = switch (event.getKeyCode()) {
            case KeyEvent.VK_LEFT -> this.navigationService.backAction();
            case KeyEvent.VK_RIGHT -> this.navigationService.forwardAction();
            default -> null;
        };
        if (action == null || !action.isEnabled()) {
            return false;
        }
        action.actionPerformed(new ActionEvent(event.getSource(), ActionEvent.ACTION_PERFORMED, "history"));
        event.consume();
        return true;
    }

    private void handleHistoryMouseButton(MouseEvent event) {
        if (event.getID() != MouseEvent.MOUSE_PRESSED || !(event.getSource() instanceof Component component)) {
            return;
        }
        if (SwingUtilities.getWindowAncestor(component) == null) {
            return;
        }
        Action action = switch (event.getButton()) {
            case 4 -> this.navigationService.backAction();
            case 5 -> this.navigationService.forwardAction();
            default -> null;
        };
        if (action == null || !action.isEnabled()) {
            return;
        }
        action.actionPerformed(new ActionEvent(event.getSource(), ActionEvent.ACTION_PERFORMED, "history"));
        event.consume();
    }

    public void openSearchEverywhere() {
        if (this.searchEverywherePopup == null) {
            this.searchEverywherePopup = new SearchEverywherePopup(this, indexLoader, this::searchRuntime, target -> navigation().navigate(target));
        }
        this.searchEverywherePopup.open();
    }

    public void openSearchEverywhere(NavigationTarget.ModuleSearch search) {
        if (this.searchEverywherePopup == null) {
            this.searchEverywherePopup = new SearchEverywherePopup(this, indexLoader, this::searchRuntime, target -> navigation().navigate(target));
        }
        this.searchEverywherePopup.open(search.moduleIds(), search.query());
    }

    private RuntimeBinding searchRuntime() {
        ProjectScope scope = project.get();
        return scope == null ? null : scope.runtime();
    }

    public EditorTabs getEditorTabs() {
        return this.editorTabs;
    }

    public NavigationService navigation() {
        return this.navigationService;
    }

    @Override public void refreshProfile() {
        setDebuggerState(debugger.status());
        this.navigationService.projectChanged(project.get());
        this.fileTreeView.reloadProfile();
        refreshActions();
    }

    @Override public boolean prepareProjectSwitch() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Project views must close on the EDT");
        if (!this.editorTabs.canCloseAll()) return false;
        setEnabled(false);
        return true;
    }

    @Override public boolean closeProjectViews() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Project views must close on the EDT");
        this.editorTabs.closeMatching(editor -> true);
        if (this.editorTabs.getTabCount() != 0) return false;
        closeProjectWindows();
        this.statusBar.setEditor(null);
        editorTabs.astCache().clear();
        setEnabled(false);
        return true;
    }

    private void closeProjectWindows() {
        for (Window window : getOwnedWindows()) window.dispose();
        if (this.debuggerWindow != null) this.debuggerWindow.dispose();
        if (this.breakpointsWindow != null) this.breakpointsWindow.dispose();
        if (this.evaluateExpressionWindow != null) this.evaluateExpressionWindow.dispose();
        if (this.searchEverywherePopup != null) this.searchEverywherePopup.dispose();
        if (this.snippetExecutions != null) this.snippetExecutions.close();
        this.debuggerWindow = null;
        this.breakpointsWindow = null;
        this.evaluateExpressionWindow = null;
        this.searchEverywherePopup = null;
        this.snippetExecutions = null;
    }

    public void refreshRuntimeSources() {
        UIUtils.onEdt(this.fileTreeView::reloadProfile);
    }

    private void refreshActions() {
        boolean hasProfile = project.get() != null;
        this.scriptMenu.setVisible(hasProfile);
        this.evaluateExpressionAction.setEnabled(scripts.isConnected());
        this.newScriptAction.setEnabled(hasProfile);
        this.debuggerState.setVisible(hasProfile);
    }

    @Override public void setGameStatus(ServiceStatus status) {
        this.statusBar.setGameStatus(status);
        if (status.state() != ServiceStatus.State.AVAILABLE && this.snippetExecutions != null) {
            this.snippetExecutions.runtimeDisconnected();
        }
        refreshActions();
    }

    @Override public void setMcpStatus(ServiceStatus status) {
        this.statusBar.setMcpStatus(status);
    }

    @Override public void setRuntimeIndexStatus(RuntimeIndexService.Status status) {
        this.statusBar.setRuntimeStatus(status);
    }
}
