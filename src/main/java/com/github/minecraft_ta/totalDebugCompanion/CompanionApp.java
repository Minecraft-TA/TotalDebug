package com.github.minecraft_ta.totalDebugCompanion;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.github.minecraft_ta.totalDebugCompanion.jdt.BaseScript;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.decompile.CompanionDecompilationService;
import com.github.minecraft_ta.totalDebugCompanion.mcp.CodeModeJobService;
import com.github.minecraft_ta.totalDebugCompanion.mcp.CompanionMcpServer;
import com.github.minecraft_ta.totalDebugCompanion.messages.session.RetryRuntimeInventoryMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.session.RuntimeInventoryMessage;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.minecraft_ta.totalDebugCompanion.search.reference.ReferenceSearchService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionTimeouts;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.syntax.ManifestTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.syntax.TomlTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.tth05.scnet.Server;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.jindex.ClassIndex;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

public final class CompanionApp {
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
    private static final CountDownLatch EXIT = new CountDownLatch(1);

    public static Server SERVER;
    private static CompanionSession session;
    private static CompanionLaunchConfiguration launchConfiguration;
    private static volatile CompanionProfile profile;
    private static volatile CompanionDecompilationService decompilationService;
    private static volatile ReferenceSearchService referenceSearchService;
    private static volatile CodeInsightService codeInsightService;
    private static RuntimeIndexService runtimeIndexService;
    private static volatile Path activeIndexFile;
    private static volatile String activeRuntimeSignature;
    private static final List<PendingClassOpen> pendingClassOpens = new ArrayList<>();
    private static CompanionMcpServer mcpServer;
    private static volatile boolean uiStarted;

    private record PendingClassOpen(String binaryName, int targetType, String targetIdentifier) {
    }

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
            runtimeIndexService = new RuntimeIndexService(CompanionApp::installRuntimeSnapshot);
            runtimeIndexService.addStatusListener(CompanionApp::updateRuntimeIndexUi);
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
                    CompanionMcpServer current = mcpServer;
                    if (current != null) {
                        current.runtimeDisconnected();
                    }
                }

                @Override
                public void runtimeInventory(RuntimeInventoryMessage message) {
                    handleRuntimeInventory(message);
                }
            });
            SERVER = session.server();
            startMcpServer();
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
            closeMcpServer();
            if (session != null) {
                session.close();
            }
            if (runtimeIndexService != null) {
                runtimeIndexService.close();
            }
            closeDecompilationService();
            closeReferenceSearchService();
            closeCodeInsightService();
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
        if (profileChanged) {
            closeDecompilationService();
            closeReferenceSearchService();
            closeCodeInsightService();
            CompanionClassIndex.close();
            activeIndexFile = null;
            activeRuntimeSignature = null;
        }
        profile = requested;
        setupDataDirectories();
        if (persist) {
            requested.writeAtomically(launchConfiguration.profileFile());
        }
        if (uiStarted && profileChanged) {
            refreshUiProfile();
        }
        if (profileChanged && runtimeIndexService != null) {
            runtimeIndexService.restore(requested.dataDirectory());
        }
    }

    private static void validateProfile(CompanionProfile requested) throws IOException {
        Files.createDirectories(requested.dataDirectory());
        if (!Files.isDirectory(requested.workspaceDirectory())) {
            throw new IOException("Minecraft workspace not found");
        }
    }

    private static void handleRuntimeInventory(RuntimeInventoryMessage message) {
        CompanionProfile current = profile;
        if (current == null || runtimeIndexService == null) {
            return;
        }
        switch (message.state()) {
            case RuntimeInventoryMessage.PREPARING -> runtimeIndexService.waiting(
                    message.detail().isBlank() ? "Minecraft is preparing runtime sources" : message.detail()
            );
            case RuntimeInventoryMessage.AVAILABLE -> runtimeIndexService.accept(
                    current.dataDirectory(),
                    message.inventoryId(),
                    Path.of(message.inventoryFile())
            );
            case RuntimeInventoryMessage.FAILED -> runtimeIndexService.failedBeforeBuild(message.detail());
            default -> runtimeIndexService.failedBeforeBuild("Minecraft sent an unknown runtime inventory state");
        }
    }

    private static synchronized void installRuntimeSnapshot(RuntimeIndexService.ReadySnapshot snapshot) {
        CompanionProfile current = requireProfile();
        CompanionDecompilationService replacement;
        try {
            replacement = new CompanionDecompilationService(
                    snapshot.signature(),
                    current.dataDirectory(),
                    RuntimeSnapshotBytecodeSource.fromIndexedSources(snapshot.sources(), snapshot.index())
            );
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to activate the runtime class index", exception);
        }

        closeDecompilationService();
        closeReferenceSearchService();
        closeCodeInsightService();
        CompanionClassIndex.replace(snapshot.index());
        decompilationService = replacement;
        referenceSearchService = new ReferenceSearchService(
                CompanionClassIndex::get,
                new RuntimeSourceCatalog(snapshot.sources())
        );
        codeInsightService = new CodeInsightService(
                CompanionClassIndex::get,
                new RuntimeSourceCatalog(snapshot.sources())
        );
        activeIndexFile = snapshot.indexFile();
        activeRuntimeSignature = snapshot.signature();
        prewarmJavaParser();

        List<PendingClassOpen> queued = List.copyOf(pendingClassOpens);
        pendingClassOpens.clear();
        for (PendingClassOpen pending : queued) {
            replacement.openClass(pending.binaryName(), pending.targetType(), pending.targetIdentifier());
        }
    }

    static void configureWithoutSession(CompanionProfile developmentProfile) {
        try {
            activateProfile(Objects.requireNonNull(developmentProfile, "developmentProfile"), false);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to configure the UI profile", exception);
        }
    }

    static void configureWithoutSession(
            CompanionProfile developmentProfile,
            Path indexFile,
            List<Path> runtimeSources,
            String runtimeSignature
    ) {
        configureWithoutSession(developmentProfile);
        try {
            ClassIndex index = ClassIndex.fromFile(indexFile.toString());
            List<RuntimeSnapshotBytecodeSource.Source> sources = new ArrayList<>();
            for (int sourceId = 0; sourceId < runtimeSources.size(); sourceId++) {
                sources.add(new RuntimeSnapshotBytecodeSource.Source(sourceId, runtimeSources.get(sourceId)));
            }
            installRuntimeSnapshot(new RuntimeIndexService.ReadySnapshot(
                    "ui-development",
                    runtimeSignature,
                    indexFile,
                    sources,
                    index
            ));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to configure the UI class index", exception);
        }
    }

    static void configureLookAndFeel() {
        configureFonts();
        if (FlatLaf.supportsNativeWindowDecorations()) {
            JFrame.setDefaultLookAndFeelDecorated(true);
            JDialog.setDefaultLookAndFeelDecorated(true);
        }
        ThemeManager.installInitialTheme();
        configureTokenMakers();
    }

    static void configureTokenMakers() {
        TokenMakerFactory factory = TokenMakerFactory.getDefaultInstance();
        if (!(factory instanceof AbstractTokenMakerFactory mappings)) {
            throw new IllegalStateException("RSyntaxTextArea token factory does not support custom mappings");
        }
        mappings.putMapping(RSyntaxTextArea.SYNTAX_STYLE_JAVA, CustomJavaTokenMaker.class.getName());
        mappings.putMapping(FileTypeResolver.SYNTAX_STYLE_TOML, TomlTokenMaker.class.getName());
        mappings.putMapping(FileTypeResolver.SYNTAX_STYLE_MANIFEST, ManifestTokenMaker.class.getName());
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

    private static void startMcpServer() throws Exception {
        CodeModeJobService jobs = new CodeModeJobService(
                SERVER,
                () -> hasCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION),
                CompanionApp::runtimeContext,
                launchConfiguration.appHome().resolve("mcp").resolve("artifacts")
        );
        CompanionMcpServer server = new CompanionMcpServer(
                launchConfiguration.appHome(),
                () -> hasProfile() ? getWorkspaceDirectory() : null,
                () -> activeIndexFile,
                jobs
        );
        try {
            server.start();
            mcpServer = server;
            System.err.println("TotalDebug Companion MCP listening at " + server.endpointUrl());
        } catch (Exception exception) {
            server.close();
            throw exception;
        }
    }

    private static void closeMcpServer() {
        CompanionMcpServer server = mcpServer;
        mcpServer = null;
        if (server != null) {
            server.close();
        }
    }

    private static Map<String, Object> runtimeContext() {
        CompanionProfile current = profile;
        if (current == null) {
            return Map.of();
        }
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("profile_id", current.id());
        context.put("workspace_directory", current.workspaceDirectory().toString());
        if (activeRuntimeSignature != null) {
            context.put("runtime_signature", activeRuntimeSignature);
        }
        if (activeIndexFile != null) {
            context.put("index_file", activeIndexFile.toString());
        }
        return Map.copyOf(context);
    }

    private static void startUi() throws InvocationTargetException, InterruptedException {
        SwingUtilities.invokeAndWait(() -> {
            uiStarted = true;
            MainWindow.INSTANCE.setSize(1280, 720);
            MainWindow.INSTANCE.setRuntimeIndexStatus(getRuntimeIndexStatus());
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

    private static void updateRuntimeIndexUi(RuntimeIndexService.Status status) {
        if (!uiStarted) {
            return;
        }
        SwingUtilities.invokeLater(() -> MainWindow.INSTANCE.setRuntimeIndexStatus(status));
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

    public static boolean isMcpListening() {
        return mcpServer != null;
    }

    public static String getMcpEndpoint() {
        CompanionMcpServer current = mcpServer;
        return current == null ? "unavailable" : current.endpointUrl();
    }

    public static boolean hasProfile() {
        return profile != null;
    }

    public static boolean send(AbstractMessage message) {
        CompanionSession current = session;
        return current != null && current.send(message);
    }

    public static void openClass(String binaryName) {
        openClass(binaryName, -1, "");
    }

    public static void openClass(String binaryName, int targetType, String targetIdentifier) {
        CompanionDecompilationService service = decompilationService;
        if (service == null) {
            synchronized (CompanionApp.class) {
                service = decompilationService;
                if (service == null) {
                    pendingClassOpens.add(new PendingClassOpen(binaryName, targetType, targetIdentifier));
                    return;
                }
            }
        }
        service.openClass(binaryName, targetType, targetIdentifier);
    }

    public static Path getRootPath() {
        return requireProfile().dataDirectory();
    }

    public static Path getIndexFile() {
        Path indexFile = activeIndexFile;
        if (indexFile == null) {
            throw new IllegalStateException("Class index is not ready");
        }
        return indexFile;
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

    public static CodeInsightService getCodeInsightService() {
        CodeInsightService service = codeInsightService;
        if (service == null) {
            throw new IllegalStateException("Code insight is unavailable");
        }
        return service;
    }

    public static CompanionDecompilationService getDecompilationService() {
        CompanionDecompilationService service = decompilationService;
        if (service == null) {
            throw new IllegalStateException("Decompilation is unavailable");
        }
        return service;
    }

    public static RuntimeIndexService.Status getRuntimeIndexStatus() {
        RuntimeIndexService service = runtimeIndexService;
        return service == null
                ? new RuntimeIndexService.Status(RuntimeIndexService.Phase.WAITING, "Waiting for runtime inventory", null)
                : service.status();
    }

    public static void addRuntimeIndexStatusListener(Consumer<RuntimeIndexService.Status> listener) {
        RuntimeIndexService service = runtimeIndexService;
        if (service != null) {
            service.addStatusListener(listener);
        } else {
            listener.accept(getRuntimeIndexStatus());
        }
    }

    public static void retryRuntimeIndex() {
        RuntimeIndexService service = runtimeIndexService;
        if (service != null) {
            service.waiting("Requesting runtime inventory again");
        }
        send(new RetryRuntimeInventoryMessage());
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

    private static void closeCodeInsightService() {
        CodeInsightService service = codeInsightService;
        codeInsightService = null;
        if (service != null) {
            service.close();
        }
    }

    private static void closeDecompilationService() {
        CompanionDecompilationService service = decompilationService;
        decompilationService = null;
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
