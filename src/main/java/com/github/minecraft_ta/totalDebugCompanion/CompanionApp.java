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
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionTimeouts;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.tth05.scnet.Server;
import com.github.tth05.scnet.message.AbstractMessage;
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
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public final class CompanionApp {
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
    private static final CountDownLatch EXIT = new CountDownLatch(1);

    public static Server SERVER;
    private static CompanionSession session;
    private static CompanionLaunchConfiguration launchConfiguration;
    private static volatile CompanionProfile profile;
    private static volatile ReferenceSearchService referenceSearchService;
    private static volatile boolean uiStarted;

    private CompanionApp() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), CompanionTimeouts.DEFAULT));
    }

    static int run(String[] args, Map<String, String> environment, CompanionTimeouts timeouts) {
        FileChannel lockChannel = null;
        FileLock instanceLock = null;
        boolean ownsInstance = false;
        try {
            Objects.requireNonNull(timeouts, "timeouts");
            launchConfiguration = CompanionLaunchConfiguration.parse(args, environment);
            Files.createDirectories(launchConfiguration.appHome());
            lockChannel = FileChannel.open(
                    launchConfiguration.lockFile(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );
            try {
                instanceLock = lockChannel.tryLock();
            } catch (OverlappingFileLockException exception) {
                instanceLock = null;
            }
            if (instanceLock == null) {
                return 0;
            }
            ownsInstance = true;

            Files.deleteIfExists(launchConfiguration.descriptorFile());
            String token = newInstanceToken();
            writeSecret(launchConfiguration.keyFile(), token);

            GlobalConfig.getInstance().loadFrom(launchConfiguration.appHome());
            configureLookAndFeel();
            restoreProfile();

            session = new CompanionSession(token, CompanionApp::attach, new CompanionSession.Listener() {
                @Override
                public void connecting() {
                    updateUiState("Connecting");
                }

                @Override
                public void connected(long capabilities) {
                    updateUiState("Connected");
                }

                @Override
                public void disconnected() {
                    updateUiState("Offline");
                }
            });
            SERVER = session.server();
            startUi();
            session.bindAndPublish(launchConfiguration);
            updateUiState("Offline");

            EXIT.await();
            return 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            return 1;
        } finally {
            GlobalConfig.getInstance().saveNow();
            if (session != null) {
                session.close();
            }
            closeReferenceSearchService();
            CompanionClassIndex.close();
            stopUiAfterFailure();
            if (ownsInstance) {
                cleanupPublishedInstance();
            }
            if (instanceLock != null) {
                try {
                    instanceLock.release();
                } catch (IOException exception) {
                    exception.printStackTrace(System.err);
                }
            }
            if (lockChannel != null) {
                try {
                    lockChannel.close();
                } catch (IOException exception) {
                    exception.printStackTrace(System.err);
                }
            }
        }
    }

    private static synchronized void attach(
            com.github.minecraft_ta.totalDebugCompanion.messages.session.ClientHelloMessage hello,
            long capabilities
    ) throws IOException {
        CompanionProfile requested;
        try {
            requested = CompanionProfile.fromHello(hello, capabilities);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid Minecraft profile", exception);
        }
        activateProfile(requested, true);
    }

    private static void restoreProfile() {
        Path profileFile = launchConfiguration.profileFile();
        if (!Files.isRegularFile(profileFile)) {
            return;
        }
        try {
            activateProfile(CompanionProfile.read(profileFile), false);
        } catch (IOException | RuntimeException exception) {
            System.err.println("Ignoring saved profile: " + exception.getMessage());
        }
    }

    private static void activateProfile(CompanionProfile requested, boolean persist) throws IOException {
        validateProfile(requested);
        CompanionProfile current = profile;
        boolean profileChanged = !requested.equals(current);
        boolean sameSnapshot = current != null
                && current.id().equals(requested.id())
                && current.runtimeSignature().equals(requested.runtimeSignature())
                && current.indexFile().equals(requested.indexFile())
                && current.runtimeSourceManifest().equals(requested.runtimeSourceManifest());

        if (!sameSnapshot) {
            List<Path> runtimeSources = RuntimeSourceManifest.read(requested.runtimeSourceManifest());
            ReferenceSearchService replacement = new ReferenceSearchService(runtimeSources);
            try {
                CompanionClassIndex.replace(requested.indexFile());
            } catch (RuntimeException exception) {
                replacement.close();
                throw new IOException("Unable to open the class index", exception);
            }
            ReferenceSearchService previous = referenceSearchService;
            referenceSearchService = replacement;
            if (previous != null) {
                previous.close();
            }
        }

        profile = requested;
        setupDataDirectories();
        if (persist) {
            requested.writeAtomically(launchConfiguration.profileFile());
        }
        if (uiStarted && profileChanged) {
            refreshUiProfile();
        }
        prewarmJavaParser();
    }

    private static void validateProfile(CompanionProfile requested) throws IOException {
        Files.createDirectories(requested.dataDirectory());
        if (!Files.isRegularFile(requested.indexFile())) {
            throw new IOException("Class index not found");
        }
        if (!Files.isRegularFile(requested.runtimeSourceManifest())) {
            throw new IOException("Runtime sources not found");
        }
        if (!Files.isDirectory(requested.workspaceDirectory())) {
            throw new IOException("Minecraft workspace not found");
        }
    }

    static void configureWithoutSession(CompanionProfile developmentProfile) {
        try {
            activateProfile(Objects.requireNonNull(developmentProfile, "developmentProfile"), false);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to configure the UI profile", exception);
        }
    }

    static void configureLookAndFeel() {
        configureFonts();
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
        if (supportsCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION)) {
            Files.createDirectories(getRootPath().resolve("scripts"));
            BaseScript.writeToFileIfNotExists();
        }
    }

    private static void prewarmJavaParser() {
        if (!CompanionClassIndex.isOpen()) {
            return;
        }
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
        SwingUtilities.invokeAndWait(() -> {
            uiStarted = true;
            MainWindow.INSTANCE.setSize(1280, 720);
            MainWindow.INSTANCE.setVisible(true);
            UIUtils.centerJFrame(MainWindow.INSTANCE);
            ToolTipManager.sharedInstance().setInitialDelay(200);
        });
    }

    private static void refreshUiProfile() {
        Runnable refresh = MainWindow.INSTANCE::refreshProfile;
        if (SwingUtilities.isEventDispatchThread()) {
            refresh.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(refresh);
            } catch (InvocationTargetException exception) {
                throw new IllegalStateException("Unable to refresh the Companion UI", exception.getCause());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while refreshing the Companion UI", exception);
            }
        }
    }

    private static void updateUiState(String state) {
        if (!uiStarted) {
            return;
        }
        SwingUtilities.invokeLater(() -> MainWindow.INSTANCE.setConnectionState(state));
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

    public static void exit() {
        EXIT.countDown();
    }

    public static boolean hasCapability(long capability) {
        return session != null && session.hasCapability(capability);
    }

    public static boolean supportsCapability(long capability) {
        CompanionProfile current = profile;
        return current != null && (current.supportedCapabilities() & capability) == capability;
    }

    public static boolean isConnected() {
        return session != null && session.isConnected();
    }

    public static boolean hasProfile() {
        return profile != null;
    }

    public static boolean send(AbstractMessage message) {
        CompanionSession current = session;
        return current != null && current.send(message);
    }

    public static Path getRootPath() {
        return requireProfile().dataDirectory();
    }

    public static Path getIndexFile() {
        return requireProfile().indexFile();
    }

    public static Path getRuntimeSourceManifest() {
        return requireProfile().runtimeSourceManifest();
    }

    public static Path getWorkspaceDirectory() {
        return requireProfile().workspaceDirectory();
    }

    public static ReferenceSearchService getReferenceSearchService() {
        ReferenceSearchService service = referenceSearchService;
        if (service == null) {
            throw new IllegalStateException("Reference search is unavailable");
        }
        return service;
    }

    private static CompanionProfile requireProfile() {
        CompanionProfile current = profile;
        if (current == null) {
            throw new IllegalStateException("No Minecraft profile is loaded");
        }
        return current;
    }

    private static void closeReferenceSearchService() {
        ReferenceSearchService service = referenceSearchService;
        referenceSearchService = null;
        if (service != null) {
            service.close();
        }
    }

    private static String newInstanceToken() {
        byte[] token = new byte[32];
        TOKEN_RANDOM.nextBytes(token);
        return HexFormat.of().formatHex(token);
    }

    private static void writeSecret(Path keyFile, String token) throws IOException {
        Path parent = Objects.requireNonNull(keyFile.getParent(), "Key file has no parent");
        Path staged = Files.createTempFile(parent, ".instance-key-", ".tmp");
        try {
            Files.writeString(staged, token, StandardCharsets.US_ASCII);
            try {
                Files.setPosixFilePermissions(staged, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                ));
            } catch (UnsupportedOperationException ignored) {
            }
            Files.move(staged, keyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void cleanupPublishedInstance() {
        CompanionLaunchConfiguration current = launchConfiguration;
        if (current == null) {
            return;
        }
        try {
            Files.deleteIfExists(current.descriptorFile());
            Files.deleteIfExists(current.keyFile());
        } catch (IOException exception) {
            exception.printStackTrace(System.err);
        }
    }
}
