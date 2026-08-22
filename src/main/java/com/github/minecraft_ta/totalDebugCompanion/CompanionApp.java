package com.github.minecraft_ta.totalDebugCompanion;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.github.minecraft_ta.totalDebugCompanion.jdt.BaseScript;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.messages.FocusWindowMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.ReadyMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.chunkGrid.ChunkGridDataMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.chunkGrid.ChunkGridRequestInfoUpdateMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.chunkGrid.ReceiveDataStateMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.chunkGrid.UpdateFollowPlayerStateMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.codeView.DecompileOrOpenMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.packetLogger.*;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.RunScriptMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.ScriptStatusMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.StopScriptMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.search.OpenSearchResultsMessage;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.SimpleMenuBarBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.FileUtils;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.tth05.scnet.IConnectedListener;
import com.github.tth05.scnet.Server;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

import javax.swing.*;
import javax.swing.plaf.BorderUIResource;
import javax.swing.plaf.ColorUIResource;
import java.awt.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CompanionApp {

    public static final Server SERVER = new Server();
    private static Path ROOT_PATH;

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Missing argument");
            return;
        }

        ROOT_PATH = Paths.get(args[0]).toAbsolutePath().normalize();
        if (!Files.exists(ROOT_PATH)) {
            System.out.println("Path does not exist");
            return;
        }

        int id = 1;
        SERVER.getMessageProcessor().registerMessage((short) id++, ReadyMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, DecompileOrOpenMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, OpenSearchResultsMessage.class);

        SERVER.getMessageProcessor().registerMessage((short) id++, ReceiveDataStateMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, ChunkGridDataMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, ChunkGridRequestInfoUpdateMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, UpdateFollowPlayerStateMessage.class);

        SERVER.getMessageProcessor().registerMessage((short) id++, RunScriptMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, ScriptStatusMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, StopScriptMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, FocusWindowMessage.class);

        SERVER.getMessageProcessor().registerMessage((short) id++, PacketLoggerStateChangeMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, IncomingPacketsMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, OutgoingPacketsMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, ClearPacketsMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, ChannelListMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, SetChannelMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, PacketContentMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, CapturePacketMessage.class);
        SERVER.getMessageProcessor().registerMessage((short) id++, BlockPacketMessage.class);

        SERVER.getMessageBus().listenAlways(DecompileOrOpenMessage.class, DecompileOrOpenMessage::handle);
        SERVER.getMessageBus().listenAlways(OpenSearchResultsMessage.class, OpenSearchResultsMessage::handle);
        SERVER.getMessageBus().listenAlways(FocusWindowMessage.class, (m) -> UIUtils.focusWindow(MainWindow.INSTANCE));
        SERVER.bind(new InetSocketAddress(25570));

        FlatDarculaLaf.setup();
        TokenMakerFactory.setDefaultInstance(new AbstractTokenMakerFactory() {
            @Override
            protected void initTokenMakerMap() {
                putMapping(RSyntaxTextArea.SYNTAX_STYLE_JAVA, CustomJavaTokenMaker.class.getName());
            }
        });

        setupScripts();
        startUI();
    }

    private static void startUI() {
        UIManager.put("SplitPaneDivider.style", "plain");
        UIManager.put("Component.focusColor", new ColorUIResource(new Color(0, 0, 0, 0)));
        UIManager.put("TabbedPane.tabInsets", new Insets(0, 10, 0, 10));
        UIManager.put("TabbedPane.tabHeight", 25);
        UIManager.put("Slider.focusedColor", new ColorUIResource(new Color(0, 0, 0, 0)));
        UIManager.put("Table.focusSelectedCellHighlightBorder", new BorderUIResource(BorderFactory.createEmptyBorder(0, 5, 0, 0)));
        UIManager.put("Table.focusCellHighlightBorder", new BorderUIResource(BorderFactory.createEmptyBorder(0, 3, 0, 0)));
        UIManager.put("Tree.selectionBackground", new ColorUIResource(new Color(5 / 255f, 127 / 255f, 242 / 255f, 0.5f)));
        UIManager.put("List.selectionBackground", new ColorUIResource(new Color(5 / 255f, 127 / 255f, 242 / 255f, 0.5f)));
        UIManager.put("TitlePane.unifiedBackground", false);
        UIManager.put("MenuBar.border", new SimpleMenuBarBorder());

        SwingUtilities.invokeLater(() -> {
            MainWindow.INSTANCE.setSize(1280, 720);
            MainWindow.INSTANCE.setVisible(true);
            UIUtils.centerJFrame(MainWindow.INSTANCE);

            ToolTipManager.sharedInstance().setInitialDelay(200);

            SERVER.getMessageProcessor().enqueueMessage(new ReadyMessage());
            // After this point, we're always ready
            SERVER.addConnectionListener((IConnectedListener) () -> SERVER.getMessageProcessor().enqueueMessage(new ReadyMessage()));
        });
    }

    private static void setupScripts() {
        //This forces a lot of JDT class loading. By doing this here, we don't have to do it later when opening a file,
        // thus making the UI more responsive.
        new Thread(() -> {
            ASTParser parser = JdtConfiguration.createParser();
            parser.setSource(new CompilationUnitImpl("Test", "class Test{}"));
            parser.setResolveBindings(true);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            var ast = (CompilationUnit) parser.createAST(null);
        }).start();

        FileUtils.createIfNotExists(ROOT_PATH.resolve("scripts"), true);
        FileUtils.createIfNotExists(ROOT_PATH.resolve("decompiled-files"), true);
        BaseScript.writeToFileIfNotExists();
    }

    public static Path getRootPath() {
        return ROOT_PATH;
    }
}
