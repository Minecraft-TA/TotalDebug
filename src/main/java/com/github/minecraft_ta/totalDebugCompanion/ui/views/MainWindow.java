package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.model.PacketLoggerView;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.ApplicationStatusBar;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.BreakpointsWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerActions;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerShortcuts;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.WorkspacePanel;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeView;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeViewHeader;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class MainWindow extends JFrame implements AWTEventListener {

    public static final MainWindow INSTANCE = new MainWindow();

    private final EditorTabs editorTabs = new EditorTabs();
    private final FileTreeView fileTreeView;
    private final NavigationService navigationService;
    private final JMenu toolsMenu = new JMenu("Tools");
    private final JMenu scriptMenu = new JMenu("Script");
    private final JButton debuggerState = new JButton("Debugger: Unavailable", Icons.DEBUG);
    private final ApplicationStatusBar statusBar;
    private final Action chunkGridAction;
    private final Action packetLoggerAction;
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
    private MainWindow() {
        setAutoRequestFocus(false);

        this.fileTreeView = new FileTreeView(target -> navigation().navigate(target));
        this.navigationService = new NavigationService(this, this.editorTabs, this.fileTreeView);
        this.statusBar = new ApplicationStatusBar(target -> this.navigationService.navigate(target));
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
                new SettingsWindow(MainWindow.this).setVisible(true);
            }
        });
        menuBar.add(fileMenu);

        this.chunkGridAction = new AbstractAction("Chunk Grid", Icons.OVERLAY_MODE) {
            @Override
            public void actionPerformed(ActionEvent e) {
                ChunkGridWindow.open();
            }
        };
        this.packetLoggerAction = new AbstractAction("Packet Logger", Icons.UP_DOWN) {
            @Override
            public void actionPerformed(ActionEvent e) {
                editorTabs.focusOrCreateIfAbsent(PacketLoggerView.class, v -> true, PacketLoggerView::new);
            }
        };
        this.toolsMenu.add(this.chunkGridAction);
        this.toolsMenu.add(this.packetLoggerAction);
        menuBar.add(this.toolsMenu);

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
                var window = new CreateScriptWindow(editorTabs);
                window.setVisible(true);
                UIUtils.centerJFrame(window);
            }
        };
        this.scriptMenu.add(this.newScriptAction);
        menuBar.add(this.scriptMenu);
        menuBar.add(Box.createHorizontalGlue());
        DebuggerSessionController debugger = CompanionApp.getDebuggerController();
        this.debuggerActions = new DebuggerActions(debugger);
        this.debuggerShortcuts = new DebuggerShortcuts(this.debuggerActions);
        this.debuggerShortcuts.install(this);
        this.debuggerListener = new DebuggerSessionController.Listener() {
            @Override
            public void statusChanged(DebuggerSessionController.Status status) {
                SwingUtilities.invokeLater(() -> {
                    setDebuggerState(status);
                    if (status.phase() == DebuggerSessionController.Phase.PAUSED) {
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
                CompanionApp.exit();
            }
        });
        setTitle("TotalDebug Companion");
        updateWindowIcon(ThemeManager.current());
        ThemeManager.addThemeChangeListener(this::updateWindowIcon);
        refreshProfile();

        Toolkit.getDefaultToolkit().addAWTEventListener(
                this,
                AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK
        );
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
                    this,
                    debugger,
                    this.debuggerActions,
                    this.debuggerShortcuts,
                    (frame, activateEditor) -> CompanionApp.openDebugFrame(frame, activateEditor),
                    () -> breakpointsWindow(debugger).showWindow(),
                    target -> this.navigationService.navigate(target)
            );
        }
        return this.debuggerWindow;
    }

    private BreakpointsWindow breakpointsWindow(DebuggerSessionController debugger) {
        if (this.breakpointsWindow == null) {
            this.breakpointsWindow = new BreakpointsWindow(
                    this,
                    debugger,
                    target -> this.navigationService.navigate(target)
            );
        }
        return this.breakpointsWindow;
    }

    private EvaluateExpressionWindow evaluateExpressionWindow() {
        if (this.snippetExecutions == null) {
            this.snippetExecutions = new SnippetExecutionService();
        }
        if (this.evaluateExpressionWindow == null) {
            this.evaluateExpressionWindow = new EvaluateExpressionWindow(this, this.snippetExecutions);
        }
        return this.evaluateExpressionWindow;
    }

    public void showDebuggerValue(DebugEngine.StackFrame frame, DebugEngine.Variable variable) {
        SwingUtilities.invokeLater(() ->
                debuggerWindow(CompanionApp.getDebuggerController()).showVariable(frame, variable));
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
        if (!CompanionApp.hasProfile()) {
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
            this.searchEverywherePopup = new SearchEverywherePopup();
        }
        this.searchEverywherePopup.open();
    }

    public void openSearchEverywhere(NavigationTarget.ModuleSearch search) {
        if (this.searchEverywherePopup == null) {
            this.searchEverywherePopup = new SearchEverywherePopup();
        }
        this.searchEverywherePopup.open(search.moduleIds(), search.query());
    }

    public EditorTabs getEditorTabs() {
        return this.editorTabs;
    }

    public NavigationService navigation() {
        return this.navigationService;
    }

    public void revealPackage(String packageName, String ownerClassName) {
        if (ownerClassName == null || ownerClassName.isBlank()) {
            reportNavigationFailure("JDT could not resolve the class owning package " + packageName);
            return;
        }
        this.navigationService.navigate(new NavigationTarget.RuntimePackage(packageName, ownerClassName));
    }

    private void reportNavigationFailure(String message) {
        var editor = this.editorTabs.getSelectedEditor();
        var informationBar = editor == null ? null : editor.getInformationBar();
        if (informationBar != null) {
            informationBar.setDefaultInfoText(message);
        }
    }

    public void refreshProfile() {
        this.fileTreeView.reloadProfile();
        refreshActions();
    }

    public void refreshRuntimeSources() {
        if (SwingUtilities.isEventDispatchThread()) {
            this.fileTreeView.reloadProfile();
        } else {
            SwingUtilities.invokeLater(this.fileTreeView::reloadProfile);
        }
    }

    private void refreshActions() {
        boolean chunkGrid = CompanionApp.supportsCapability(CompanionProtocol.CAPABILITY_CHUNK_GRID);
        boolean packetLogger = CompanionApp.supportsCapability(CompanionProtocol.CAPABILITY_PACKET_LOGGER);
        boolean scripts = CompanionApp.supportsCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION);
        boolean debugger = CompanionApp.supportsCapability(CompanionProtocol.CAPABILITY_DEBUGGER);
        this.toolsMenu.setVisible(chunkGrid || packetLogger);
        this.chunkGridAction.setEnabled(CompanionApp.hasCapability(CompanionProtocol.CAPABILITY_CHUNK_GRID));
        this.packetLoggerAction.setEnabled(CompanionApp.hasCapability(CompanionProtocol.CAPABILITY_PACKET_LOGGER));
        this.scriptMenu.setVisible(scripts);
        this.evaluateExpressionAction.setEnabled(
                CompanionApp.hasCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION)
        );
        this.newScriptAction.setEnabled(scripts);
        this.debuggerState.setVisible(debugger);
    }

    public void setGameStatus(ServiceStatus status) {
        this.statusBar.setGameStatus(status);
        if (status.state() != ServiceStatus.State.AVAILABLE && this.snippetExecutions != null) {
            this.snippetExecutions.runtimeDisconnected();
        }
        refreshActions();
    }

    public void setMcpStatus(ServiceStatus status) {
        this.statusBar.setMcpStatus(status);
    }

    public void setRuntimeIndexStatus(RuntimeIndexService.Status status) {
        this.statusBar.setRuntimeStatus(status);
    }
}
