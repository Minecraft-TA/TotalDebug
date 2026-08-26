package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.model.PacketLoggerView;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.ApplicationStatusBar;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.WorkspacePanel;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
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
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

public class MainWindow extends JFrame implements AWTEventListener {

    public static final MainWindow INSTANCE = new MainWindow();

    private final EditorTabs editorTabs = new EditorTabs();
    private final FileTreeView fileTreeView;
    private final JMenu toolsMenu = new JMenu("Tools");
    private final JMenu scriptMenu = new JMenu("Script");
    private final ApplicationStatusBar statusBar = new ApplicationStatusBar();
    private final Action chunkGridAction;
    private final Action packetLoggerAction;
    private final Action newScriptAction;

    private long lastShiftReleasedTime = 0;
    private SearchEverywherePopup searchEverywherePopup;
    private CodeInsightService.SearchHandle packageReveal;

    private MainWindow() {
        setAutoRequestFocus(false);

        this.fileTreeView = new FileTreeView(this.editorTabs);
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

        Toolkit.getDefaultToolkit().addAWTEventListener(this, AWTEvent.KEY_EVENT_MASK);
    }

    private void updateWindowIcon(CompanionTheme theme) {
        setIconImages(Icons.createWindowIconImages(theme));
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        if (!(event instanceof KeyEvent keyEvent))
            return;

        if (keyEvent.getID() != 402 || keyEvent.getKeyCode() != KeyEvent.VK_SHIFT)
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

    public void openSearchEverywhere() {
        if (this.searchEverywherePopup == null) {
            this.searchEverywherePopup = new SearchEverywherePopup();
        }
        this.searchEverywherePopup.open();
    }

    public EditorTabs getEditorTabs() {
        return this.editorTabs;
    }

    public void revealPackage(String packageName, String ownerClassName) {
        if (ownerClassName == null) {
            reportPackageRevealFailure("JDT could not resolve the class owning package " + packageName);
            return;
        }
        if (this.packageReveal != null) {
            this.packageReveal.cancel();
        }
        this.packageReveal = CompanionApp.getCodeInsightService().locateClass(
                ownerClassName,
                new CodeInsightService.Listener<>() {
                    @Override
                    public void onCompleted(RuntimeSnapshotBytecodeSource.Source source) {
                        packageReveal = null;
                        if (source == null) {
                            reportPackageRevealFailure(
                                    "Class " + ownerClassName + " is not present in the runtime index"
                            );
                            return;
                        }
                        Optional<String> archive = workspaceArchiveName(source);
                        if (archive.isEmpty()) {
                            reportPackageRevealFailure(
                                    "Class " + ownerClassName + " is indexed outside the mods tree"
                            );
                            return;
                        }
                        fileTreeView.revealPackage(packageName, archive.get()).whenComplete((revealed, failure) ->
                                SwingUtilities.invokeLater(() -> {
                                    if (failure != null) {
                                        failure.printStackTrace(System.err);
                                        reportPackageRevealFailure("Unable to reveal package " + packageName);
                                    } else if (!revealed) {
                                        reportPackageRevealFailure(
                                                "Package " + packageName + " is not present in the owning archive"
                                        );
                                    }
                                })
                        );
                    }

                    @Override
                    public void onFailed(Throwable failure) {
                        packageReveal = null;
                        failure.printStackTrace(System.err);
                        reportPackageRevealFailure("Unable to locate package " + packageName);
                    }
                }
        );
    }

    private static Optional<String> workspaceArchiveName(RuntimeSnapshotBytecodeSource.Source source) {
        Path modsDirectory = CompanionApp.getWorkspaceDirectory().resolve("mods").toAbsolutePath().normalize();
        Path archive = source.path().toAbsolutePath().normalize();
        if (modsDirectory.equals(archive.getParent())) {
            return Optional.of(archive.getFileName().toString());
        }

        String logicalRoot = source.logicalUri();
        int nestedSeparator = logicalRoot.indexOf("!/");
        if (nestedSeparator >= 0) {
            logicalRoot = logicalRoot.substring(0, nestedSeparator);
        }
        if (!logicalRoot.startsWith("file:")) {
            return Optional.empty();
        }
        Path logicalArchive = Path.of(URI.create(logicalRoot)).toAbsolutePath().normalize();
        return modsDirectory.equals(logicalArchive.getParent())
                ? Optional.of(logicalArchive.getFileName().toString())
                : Optional.empty();
    }

    private void reportPackageRevealFailure(String message) {
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

    private void refreshActions() {
        boolean chunkGrid = CompanionApp.supportsCapability(CompanionProtocol.CAPABILITY_CHUNK_GRID);
        boolean packetLogger = CompanionApp.supportsCapability(CompanionProtocol.CAPABILITY_PACKET_LOGGER);
        boolean scripts = CompanionApp.supportsCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION);
        this.toolsMenu.setVisible(chunkGrid || packetLogger);
        this.chunkGridAction.setEnabled(CompanionApp.hasCapability(CompanionProtocol.CAPABILITY_CHUNK_GRID));
        this.packetLoggerAction.setEnabled(CompanionApp.hasCapability(CompanionProtocol.CAPABILITY_PACKET_LOGGER));
        this.scriptMenu.setVisible(scripts);
        this.newScriptAction.setEnabled(scripts);
    }

    public void setGameStatus(ServiceStatus status) {
        this.statusBar.setGameStatus(status);
        refreshActions();
    }

    public void setMcpStatus(ServiceStatus status) {
        this.statusBar.setMcpStatus(status);
    }

    public void setRuntimeIndexStatus(RuntimeIndexService.Status status) {
        this.statusBar.setRuntimeStatus(status);
    }
}
