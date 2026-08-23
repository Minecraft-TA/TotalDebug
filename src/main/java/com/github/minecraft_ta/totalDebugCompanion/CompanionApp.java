package com.github.minecraft_ta.totalDebugCompanion;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.github.minecraft_ta.totalDebugCompanion.jdt.BaseScript;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceManifest;
import com.github.minecraft_ta.totalDebugCompanion.search.reference.ReferenceSearchService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionTimeouts;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.tth05.scnet.Server;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import java.awt.Window;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CompanionApp {
    public static Server SERVER;
    private static CompanionSession session;
    private static CompanionLaunchConfiguration configuration;
    private static ReferenceSearchService referenceSearchService;
    private static volatile boolean uiStarted;

    private CompanionApp() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), CompanionTimeouts.DEFAULT));
    }

    static int run(String[] args, Map<String, String> environment, CompanionTimeouts timeouts) {
        try {
            Objects.requireNonNull(timeouts, "timeouts");
            configuration = CompanionLaunchConfiguration.parse(args, environment);
            List<Path> runtimeSources = validatePaths(configuration);
            referenceSearchService = new ReferenceSearchService(runtimeSources);
            GlobalConfig.getInstance().loadFrom(configuration.dataDirectory());

            session = new CompanionSession(configuration.consumeSessionToken());
            SERVER = session.server();
            session.bindAndPublish(configuration);
            long capabilities = session.awaitAuthentication(timeouts.authentication());
            if ((capabilities & CompanionProtocol.CORE_CAPABILITIES) != CompanionProtocol.CORE_CAPABILITIES) {
                throw new IOException(
                        "Minecraft did not negotiate all Companion core capabilities: 0x"
                                + Long.toHexString(capabilities)
                );
            }

            CompanionClassIndex.open(configuration.indexFile());
            configureLookAndFeel();
            setupDataDirectories();
            prewarmJavaParser();
            startUi();
            if (session.markUiReady()) {
                session.awaitAuthenticatedDisconnect();
            }
            stopUi();
            GlobalConfig.getInstance().saveNow();
            session.close();
            closeReferenceSearchService();
            CompanionClassIndex.close();
            return 0;
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            stopUiAfterFailure();
            GlobalConfig.getInstance().saveNow();
            if (session != null) {
                session.close();
            }
            closeReferenceSearchService();
            CompanionClassIndex.close();
            return 1;
        }
    }

    private static List<Path> validatePaths(CompanionLaunchConfiguration launchConfiguration) throws IOException {
        Files.createDirectories(launchConfiguration.dataDirectory());
        if (!Files.isRegularFile(launchConfiguration.indexFile())) {
            throw new IOException("Class index does not exist: " + launchConfiguration.indexFile());
        }
        List<Path> runtimeSources = RuntimeSourceManifest.read(launchConfiguration.runtimeSourceManifest());
        if (!Files.isDirectory(launchConfiguration.workspaceDirectory())) {
            throw new IOException("Minecraft workspace does not exist: " + launchConfiguration.workspaceDirectory());
        }
        Path descriptorParent = launchConfiguration.sessionDescriptor().getParent();
        if (descriptorParent == null || !Files.isDirectory(descriptorParent)) {
            throw new IOException("Session directory does not exist: " + descriptorParent);
        }
        return runtimeSources;
    }

    /**
     * Installs a launch configuration without a Minecraft session. Used by the UI dev harness, which
     * needs the paths {@link #getRootPath()} exposes but has no mod to hand shake with.
     */
    static void configureWithoutSession(CompanionLaunchConfiguration launchConfiguration) {
        configuration = launchConfiguration;
        try {
            referenceSearchService = new ReferenceSearchService(
                    RuntimeSourceManifest.read(launchConfiguration.runtimeSourceManifest())
            );
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to configure runtime reference search", exception);
        }
    }

    static void configureLookAndFeel() {
        configureFonts();
        // IntelliJ-style title bar with the menu bar embedded into it. Must be set before the first
        // window is constructed.
        if (FlatLaf.supportsNativeWindowDecorations()) {
            JFrame.setDefaultLookAndFeelDecorated(true);
            JDialog.setDefaultLookAndFeelDecorated(true);
        }
        ThemeManager.installInitialTheme();
        TokenMakerFactory.setDefaultInstance(new AbstractTokenMakerFactory() {
            @Override
            protected void initTokenMakerMap() {
                putMapping(RSyntaxTextArea.SYNTAX_STYLE_JAVA, CustomJavaTokenMaker.class.getName());
            }
        });
    }

    /**
     * Registers the fonts IntelliJ itself uses: Inter for the UI, JetBrains Mono for code. Must run
     * before the look and feel is installed, since that is when the base font is resolved.
     */
    private static void configureFonts() {
        FlatInterFont.installLazy();
        FlatJetBrainsMonoFont.installLazy();
        FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY);
        FlatLaf.setPreferredLightFontFamily(FlatInterFont.FAMILY_LIGHT);
        FlatLaf.setPreferredSemiboldFontFamily(FlatInterFont.FAMILY_SEMIBOLD);
        FlatLaf.setPreferredMonospacedFontFamily(FlatJetBrainsMonoFont.FAMILY);
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
        // UI defaults live in CompanionDefaultsAddon so they are reapplied on every theme switch.
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

    /** Flushes user settings before terminating from the main window's close action. */
    public static void exit() {
        GlobalConfig.getInstance().saveNow();
        System.exit(0);
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

    public static Path getRuntimeSourceManifest() {
        return requireConfiguration().runtimeSourceManifest();
    }

    public static Path getWorkspaceDirectory() {
        return requireConfiguration().workspaceDirectory();
    }

    public static ReferenceSearchService getReferenceSearchService() {
        ReferenceSearchService service = referenceSearchService;
        if (service == null) {
            throw new IllegalStateException("Reference-search service is not initialized");
        }
        return service;
    }

    private static void closeReferenceSearchService() {
        ReferenceSearchService service = referenceSearchService;
        referenceSearchService = null;
        if (service != null) {
            service.close();
        }
    }

    private static CompanionLaunchConfiguration requireConfiguration() {
        if (configuration == null) {
            throw new IllegalStateException("Companion launch configuration is not initialized");
        }
        return configuration;
    }
}
