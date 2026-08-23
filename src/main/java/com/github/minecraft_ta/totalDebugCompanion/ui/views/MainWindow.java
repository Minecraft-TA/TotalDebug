package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.model.PacketLoggerView;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeView;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeViewHeader;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class MainWindow extends JFrame implements AWTEventListener {

    public static final MainWindow INSTANCE = new MainWindow();

    private final EditorTabs editorTabs = new EditorTabs();

    private long lastShiftReleasedTime = 0;
    private SearchEverywherePopup searchEverywherePopup;

    private MainWindow() {
        var root = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT) {
            @Override
            protected void paintChildren(Graphics graphics) {
                super.paintChildren(graphics);
                Color previous = graphics.getColor();
                graphics.setColor(ThemeColors.separator());
                int separatorX = getDividerLocation() + getDividerSize() / 2;
                graphics.fillRect(separatorX, 0, 1, getHeight());
                graphics.setColor(previous);
            }
        };

        root.setLeftComponent(UIUtils.verticalLayout(new FileTreeViewHeader(), new FileTreeView(this.editorTabs)));
        root.setRightComponent(this.editorTabs);
        root.setBorder(BorderFactory.createEmptyBorder());
        root.setDividerSize(7);
        root.setDividerLocation(350);
        root.setOneTouchExpandable(false);

        getContentPane().add(root);

        var menuBar = new JMenuBar();

        var fileMenu = new JMenu("File");
        fileMenu.add(new AbstractAction("Settings...", Icons.SETTINGS) {
            @Override
            public void actionPerformed(ActionEvent e) {
                new SettingsWindow(MainWindow.this).setVisible(true);
            }
        });
        menuBar.add(fileMenu);

        var toolsMenu = new JMenu("Tools");
        if (CompanionApp.hasCapability(CompanionProtocol.CAPABILITY_CHUNK_GRID)) {
            toolsMenu.add(new AbstractAction("Chunk Grid", Icons.OVERLAY_MODE) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    ChunkGridWindow.open();
                }
            });
        }
        if (CompanionApp.hasCapability(CompanionProtocol.CAPABILITY_PACKET_LOGGER)) {
            toolsMenu.add(new AbstractAction("Packet Logger", Icons.UP_DOWN) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    editorTabs.focusOrCreateIfAbsent(PacketLoggerView.class, v -> true, PacketLoggerView::new);
                }
            });
        }
        if (toolsMenu.getItemCount() > 0) {
            menuBar.add(toolsMenu);
        }

        if (CompanionApp.hasCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION)) {
            var scriptMenu = new JMenu("Script");
            scriptMenu.add(new AbstractAction("New Script", Icons.JAVA_FILE) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var window = new CreateScriptWindow(editorTabs);
                    window.setVisible(true);
                    UIUtils.centerJFrame(window);
                }
            });
            menuBar.add(scriptMenu);
        }

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
        if (this.searchEverywherePopup == null) {
            this.searchEverywherePopup = new SearchEverywherePopup();
        }
        this.searchEverywherePopup.open();
    }

    public EditorTabs getEditorTabs() {
        return this.editorTabs;
    }
}
