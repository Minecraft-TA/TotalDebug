package com.github.minecraft_ta.totalDebugCompanion;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.github.minecraft_ta.totalDebugCompanion.jdt.BaseScript;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.SimpleMenuBarBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.tth05.scnet.Server;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

import javax.swing.BorderFactory;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.plaf.BorderUIResource;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;
import java.awt.Insets;
import java.awt.Window;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CompanionApp {
    private static final int AUTHENTICATION_TIMEOUT_SECONDS = 60;

    public static Server SERVER;
    private static CompanionSession session;
    private static CompanionLaunchConfiguration configuration;
    private static volatile boolean uiStarted;

    private CompanionApp() {
    }

    public static void main(String[] args) {
        try {
            configuration = CompanionLaunchConfiguration.parse(args, System.getenv());
            validatePaths(configuration);

            session = new CompanionSession(configuration.consumeSessionToken());
            SERVER = session.server();
            session.bindAndPublish(configuration);
            long capabilities = session.awaitAuthentication(AUTHENTICATION_TIMEOUT_SECONDS);
            if ((capabilities & CompanionProtocol.CORE_CAPABILITIES) != CompanionProtocol.CORE_CAPABILITIES) {
                throw new IOException(
                        "Minecraft did not negotiate all Companion core capabilities: 0x"
                                + Long.toHexString(capabilities)
                );
            }

            configureLookAndFeel();
            setupDataDirectories();
            prewarmJavaParser();
            startUi();
            if (session.markUiReady()) {
                session.awaitAuthenticatedDisconnect();
            }
            stopUi();
            session.close();
            System.exit(0);
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            stopUiAfterFailure();
            if (session != null) {
                session.close();
            }
            System.exit(1);
        }
    }

    private static void validatePaths(CompanionLaunchConfiguration launchConfiguration) throws IOException {
        Files.createDirectories(launchConfiguration.dataDirectory());
        if (!Files.isRegularFile(launchConfiguration.indexFile())) {
            throw new IOException("Class index does not exist: " + launchConfiguration.indexFile());
        }
        if (!Files.isDirectory(launchConfiguration.workspaceDirectory())) {
            throw new IOException("Minecraft workspace does not exist: " + launchConfiguration.workspaceDirectory());
        }
        Path descriptorParent = launchConfiguration.sessionDescriptor().getParent();
        if (descriptorParent == null || !Files.isDirectory(descriptorParent)) {
            throw new IOException("Session directory does not exist: " + descriptorParent);
        }
    }

    private static void configureLookAndFeel() {
        FlatDarculaLaf.setup();
        TokenMakerFactory.setDefaultInstance(new AbstractTokenMakerFactory() {
            @Override
            protected void initTokenMakerMap() {
                putMapping(RSyntaxTextArea.SYNTAX_STYLE_JAVA, CustomJavaTokenMaker.class.getName());
            }
        });
    }

    private static void setupDataDirectories() throws IOException {
        Files.createDirectories(getRootPath().resolve("decompiled-files"));
        if (hasCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION)) {
            Files.createDirectories(getRootPath().resolve("scripts"));
            BaseScript.writeToFileIfNotExists();
        }
    }

    private static void prewarmJavaParser() {
        Thread thread = new Thread(() -> {
            ASTParser parser = JdtConfiguration.createParser();
            parser.setSource(new CompilationUnitImpl("Test", "class Test{}"));
            parser.setResolveBindings(true);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            CompilationUnit ignored = (CompilationUnit) parser.createAST(null);
        }, "Companion JDT prewarm");
        thread.setDaemon(true);
        thread.start();
    }

    private static void startUi() throws InvocationTargetException, InterruptedException {
        UIManager.put("SplitPaneDivider.style", "plain");
        UIManager.put("Component.focusColor", new ColorUIResource(new Color(0, 0, 0, 0)));
        UIManager.put("TabbedPane.tabInsets", new Insets(0, 10, 0, 10));
        UIManager.put("TabbedPane.tabHeight", 25);
        UIManager.put("Slider.focusedColor", new ColorUIResource(new Color(0, 0, 0, 0)));
        UIManager.put(
                "Table.focusSelectedCellHighlightBorder",
                new BorderUIResource(BorderFactory.createEmptyBorder(0, 5, 0, 0))
        );
        UIManager.put(
                "Table.focusCellHighlightBorder",
                new BorderUIResource(BorderFactory.createEmptyBorder(0, 3, 0, 0))
        );
        UIManager.put("Tree.selectionBackground", new ColorUIResource(new Color(5 / 255f, 127 / 255f, 242 / 255f, 0.5f)));
        UIManager.put("List.selectionBackground", new ColorUIResource(new Color(5 / 255f, 127 / 255f, 242 / 255f, 0.5f)));
        UIManager.put("TitlePane.unifiedBackground", false);
        UIManager.put("MenuBar.border", new SimpleMenuBarBorder());

        SwingUtilities.invokeAndWait(() -> {
            uiStarted = true;
            MainWindow.INSTANCE.setSize(1280, 720);
            MainWindow.INSTANCE.setVisible(true);
            UIUtils.centerJFrame(MainWindow.INSTANCE);
            ToolTipManager.sharedInstance().setInitialDelay(200);
        });
    }

    private static void stopUi() throws InvocationTargetException, InterruptedException {
        if (!uiStarted) {
            return;
        }
        SwingUtilities.invokeAndWait(() -> {
            for (Window window : Window.getWindows()) {
                window.dispose();
            }
            uiStarted = false;
        });
    }

    private static void stopUiAfterFailure() {
        try {
            stopUi();
        } catch (InvocationTargetException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            exception.printStackTrace(System.err);
        }
    }

    public static void focusWindow() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Companion window focus must run on the Swing event thread");
        }
        UIUtils.focusWindow(MainWindow.INSTANCE);
    }

    public static boolean hasCapability(long capability) {
        return session != null && session.hasCapability(capability);
    }

    public static Path getRootPath() {
        return requireConfiguration().dataDirectory();
    }

    public static Path getIndexFile() {
        return requireConfiguration().indexFile();
    }

    public static Path getWorkspaceDirectory() {
        return requireConfiguration().workspaceDirectory();
    }

    private static CompanionLaunchConfiguration requireConfiguration() {
        if (configuration == null) {
            throw new IllegalStateException("Companion launch configuration is not initialized");
        }
        return configuration;
    }
}
