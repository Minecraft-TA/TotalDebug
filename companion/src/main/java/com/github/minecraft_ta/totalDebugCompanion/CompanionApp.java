package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.minecraft_ta.totaldebug.storage.RuntimePhase;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.decompile.CompanionDecompilationService;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugTargetDescriptor;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.mcp.CodeModeJobService;
import com.github.minecraft_ta.totalDebugCompanion.mcp.CompanionMcpServer;
import com.github.minecraft_ta.totaldebug.protocol.scnet.companion.DebugTargetMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.companion.RetryRuntimeInventoryMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.companion.RuntimeInventoryMessage;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTargets;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.minecraft_ta.totalDebugCompanion.search.reference.ReferenceSearchService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
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
import javax.swing.JOptionPane;
import javax.swing.ToolTipManager;
import java.awt.Window;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static volatile InstanceState instanceState = InstanceState.inMemory();
    private static volatile ReferenceSearchService referenceSearchService;
    private static volatile CodeInsightService codeInsightService;
    private static volatile RuntimeSourceCatalog runtimeSourceCatalog = RuntimeSourceCatalog.empty();
    private static RuntimeIndexService runtimeIndexService;
    private static volatile String evaluationClasspath;
    private static volatile Path activeIndexFile;
    private static volatile String activeRuntimeSignature;
    private static final List<PendingNavigation> pendingNavigations = new ArrayList<>();
    private static CompanionMcpServer mcpServer;
    private static volatile DebuggerSessionController debuggerController;
    private static volatile boolean uiStarted;

    private record PendingNavigation(NavigationTarget target, NavigationService.Activation activation) {
    }

    private CompanionApp() {
    }

    public static void main(String[] args) {
        int result = 1;
        try {
            var configuration = CompanionLaunchConfiguration.parse(args, System.getenv());
            var paths = configuration.paths();
            Path executable = Path.of(CompanionApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            try (var executablePin = com.github.minecraft_ta.totaldebug.storage.LaunchCache.pinRunning(paths, executable)) {
                String requestedLog = System.getProperty(com.github.minecraft_ta.totaldebug.storage.DiagnosticLogs.LOG_PROPERTY);
                var reservation = requestedLog == null
                        ? com.github.minecraft_ta.totaldebug.storage.DiagnosticLogs.reserve(paths) : null;
                try (reservation;
                     var output = com.github.minecraft_ta.totaldebug.storage.DiagnosticLogs.open(paths,
                             reservation == null ? Path.of(requestedLog) : reservation.log());
                     var print = new java.io.PrintStream(output, true, StandardCharsets.UTF_8)) {
                    // Release the inherited handles before rotating their bootstrap log on Windows.
                    System.out.close();
                    System.err.close();
                    System.setOut(print);
                    System.setErr(print);
                    result = run(args, System.getenv(), CompanionTimeouts.DEFAULT);
                }
            }
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
        }
        System.exit(result);
    }

    static int run(String[] args, Map<String, String> environment, CompanionTimeouts timeouts) {
        var startup = RuntimePhase.start("companion.startup");
        FileChannel lockChannel = null;
        FileLock instanceLock = null;
        boolean ownsInstance = false;
        try {
            Objects.requireNonNull(timeouts, "timeouts");
            launchConfiguration = CompanionLaunchConfiguration.parse(args, environment);
            Files.createDirectories(launchConfiguration.paths().run());
            AtomicFiles.cleanupAbandonedStaging(launchConfiguration.paths().run());
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
            runtimeIndexService = new RuntimeIndexService(CompanionApp.class, CompanionApp::installRuntimeSnapshot);
            runtimeIndexService.addStatusListener(CompanionApp::updateRuntimeIndexUi);
            debuggerController = createDebuggerController();
            debuggerController.setExceptionBreakpoints(
                    instanceState().breakOnCaughtExceptions(),
                    instanceState().breakOnUncaughtExceptions()
            );
            restoreProfile();

            session = new CompanionSession(token, CompanionApp::activateSessionProfile, new CompanionSession.Listener() {
                @Override
                public void connecting() {
                    updateGameStatus(new ServiceStatus(
                            ServiceStatus.State.PENDING,
                            "Connecting",
                            "Waiting for Minecraft to finish the authenticated connection."
                    ));
                }

                @Override
                public void connected() {
                    updateGameStatus(new ServiceStatus(
                            ServiceStatus.State.AVAILABLE,
                            "Connected",
                            "Minecraft is connected and authenticated."
                    ));
                }

                @Override
                public void disconnected() {
                    updateGameStatus(new ServiceStatus(
                            ServiceStatus.State.INACTIVE,
                            "Offline",
                            "Minecraft is not connected."
                    ));
                    debuggerController.clearTarget();
                    CompanionMcpServer current = mcpServer;
                    if (current != null) {
                        current.runtimeDisconnected();
                    }
                }

                @Override
                public void runtimeInventory(RuntimeInventoryMessage message) {
                    handleRuntimeInventory(message);
                }

                @Override
                public void debugTarget(DebugTargetMessage message) {
                    handleDebugTarget(message);
                }
            });
            SERVER = session.server();
            startUi();
            updateGameStatus(new ServiceStatus(
                    ServiceStatus.State.INACTIVE,
                    "Offline",
                    "Minecraft is not connected."
            ));
            session.bindAndPublish(launchConfiguration);
            startOptionalMcpServer();

            startup.close();
            EXIT.await();
            return 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            return 1;
        } finally {
            startup.close();
            try (var shutdown = RuntimePhase.start("companion.shutdown")) {
                if (runtimeIndexService != null) {
                    runtimeIndexService.close();
                }
                RuntimePhase.run("close.mcp", CompanionApp::closeMcpServer);
                if (session != null) {
                    RuntimePhase.run("close.session", session::close);
                }
                if (debuggerController != null) {
                    RuntimePhase.run("close.debugger", debuggerController::close);
                }
                RuntimePhase.run("close.decompilation", CompanionApp::closeDecompilationService);
                RuntimePhase.run("close.references", CompanionApp::closeReferenceSearchService);
                RuntimePhase.run("close.code-insight", CompanionApp::closeCodeInsightService);
                RuntimePhase.run("close.index", CompanionClassIndex::close);
                RuntimePhase.run("close.ui", CompanionApp::stopUiAfterFailure);
                try (var state = RuntimePhase.start("close.state")) {
                    GlobalConfig.getInstance().saveNow();
                    instanceState.close();
                } catch (IOException exception) {
                    exception.printStackTrace(System.err);
                }
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
    }

    private static synchronized void activateSessionProfile(
            com.github.minecraft_ta.totaldebug.protocol.scnet.companion.ClientHelloMessage hello
    ) throws IOException {
        CompanionProfile requested;
        try {
            requested = CompanionProfile.fromHello(hello);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid Minecraft profile", exception);
        }
        activateProfile(requested, true);
    }

    private static void handleDebugTarget(DebugTargetMessage message) {
        if (message.targetKind() != DebugTargetMessage.LOCAL_JVM) {
            throw new IllegalArgumentException("Unknown debug target kind: " + message.targetKind());
        }
        debuggerController.acceptTarget(new DebugTargetDescriptor(
                message.targetId(),
                message.displayName(),
                message.processId()
        ));
    }

    private static void restoreProfile() throws IOException {
        Path profileFile = launchConfiguration.profileFile();
        if (!Files.isRegularFile(profileFile)) {
            return;
        }
        activateProfile(CompanionProfile.read(profileFile), false);
    }

    private static void activateProfile(CompanionProfile requested, boolean persist) throws IOException {
        validateProfile(requested);
        CompanionProfile current = profile;
        boolean profileChanged = !requested.equals(current);
        if (profileChanged) {
            InstanceState replacementState = InstanceState.open(new InstancePaths(requested.dataDirectory()));
            try {
                instanceState.close();
            } catch (IOException exception) {
                replacementState.close();
                throw exception;
            }
            instanceState = replacementState;
            getDebuggerController().setBreakpointsMuted(instanceState.debuggerBreakpointsMuted()).join();
            getDebuggerController().setExceptionBreakpoints(instanceState.breakOnCaughtExceptions(),
                    instanceState.breakOnUncaughtExceptions()).join();
            closeDecompilationService();
            closeReferenceSearchService();
            invalidateCodeInsightService();
            CompanionClassIndex.close();
            activeIndexFile = null;
            activeRuntimeSignature = null;
            runtimeSourceCatalog = RuntimeSourceCatalog.empty();
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

    private static synchronized void handleRuntimeInventory(RuntimeInventoryMessage message) {
        CompanionProfile current = profile;
        if (current == null || runtimeIndexService == null) {
            return;
        }
        switch (message.state()) {
            case RuntimeInventoryMessage.PREPARING -> {
                runtimeIndexService.waiting(
                        message.detail().isBlank() ? "Minecraft is preparing runtime sources" : message.detail());
            }
            case RuntimeInventoryMessage.AVAILABLE -> runtimeIndexService.accept(
                    current.dataDirectory(),
                    message.inventoryId(),
                    Path.of(message.inventoryFile())
            );
            case RuntimeInventoryMessage.FAILED -> runtimeIndexService.failedBeforeBuild(message.detail());
            default -> runtimeIndexService.failedBeforeBuild("Minecraft sent an unknown runtime inventory state");
        }
    }

    private static void installRuntimeSnapshot(RuntimeIndexService.ReadySnapshot snapshot) {
        installRuntimeSnapshot(snapshot, RuntimeSnapshotBytecodeSource.fromRuntime(snapshot.sources(), snapshot.index(),
                snapshot.indexFile().getParent().resolve("inventory.json"), snapshot.inventoryId()));
    }

    private static synchronized void installRuntimeSnapshot(RuntimeIndexService.ReadySnapshot snapshot,
                                                             RuntimeSnapshotBytecodeSource bytecodeSource) {
        CompanionProfile current = requireProfile();
        closeDecompilationService();
        CompanionDecompilationService replacement;
        try {
            replacement = new CompanionDecompilationService(
                    snapshot.signature(),
                    current.dataDirectory(),
                    bytecodeSource
            );
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to activate the runtime class index", exception);
        }

        closeReferenceSearchService();
        RuntimeSourceCatalog sourceCatalog = new RuntimeSourceCatalog(snapshot.sources());
        runtimeSourceCatalog = sourceCatalog;
        evaluationClasspath = snapshot.sources().stream().map(source -> source.path().toString())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        CodeInsightService currentInsightService = codeInsightService;
        if (currentInsightService != null) {
            currentInsightService.rebind(snapshot::index, sourceCatalog);
        }
        CompanionClassIndex.replace(snapshot.index());
        decompilationService = replacement;
        referenceSearchService = new ReferenceSearchService(
                CompanionClassIndex::get,
                sourceCatalog
        );
        if (currentInsightService == null) {
            codeInsightService = new CodeInsightService(snapshot::index, sourceCatalog);
        }
        activeIndexFile = snapshot.indexFile();
        activeRuntimeSignature = snapshot.signature();
        getDebuggerController().replaceBreakpointDefinitions(
                restoreBreakpoints(snapshot.signature())
        ).join();
        if (uiStarted) {
            MainWindow.INSTANCE.refreshRuntimeSources();
        }
        prewarmJavaParser();

        List<PendingNavigation> queued = List.copyOf(pendingNavigations);
        pendingNavigations.clear();
        for (PendingNavigation pending : queued) {
            MainWindow.INSTANCE.navigation().navigate(pending.target(), pending.activation());
        }
    }

    static void configureWithoutSession(CompanionProfile developmentProfile) {
        debuggerController = createDebuggerController();
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
                Path source = runtimeSources.get(sourceId);
                sources.add(new RuntimeSnapshotBytecodeSource.Source(
                        sourceId,
                        source,
                        source.toUri().toASCIIString(),
                        new RuntimeInventory.RuntimeModule(
                                "ui-development",
                                "UI development classes",
                                RuntimeInventory.ModuleKind.LIBRARY
                        )
                ));
            }
            installRuntimeSnapshot(new RuntimeIndexService.ReadySnapshot(
                    "ui-development",
                    runtimeSignature,
                    indexFile,
                    sources,
                    index
            ), RuntimeSnapshotBytecodeSource.fromIndexedSources(sources, index));
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
        setupDataDirectories(
                getRootPath(),
                hasProfile()
        );
    }

    static void setupDataDirectories(Path rootPath, boolean scriptExecutionEnabled) throws IOException {

        if (!scriptExecutionEnabled) {
            return;
        }
        Files.createDirectories(new InstancePaths(rootPath).scripts());
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
                () -> isConnected(),
                CompanionApp::runtimeContext
        );
        CompanionMcpServer server = new CompanionMcpServer(launchConfiguration.appHome(), jobs);
        try {
            server.start();
            mcpServer = server;
            System.err.println("TotalDebug Companion MCP listening at " + server.endpointUrl());
        } catch (Exception exception) {
            server.close();
            throw exception;
        }
    }

    private static void startOptionalMcpServer() {
        updateMcpStatus(new ServiceStatus(
                ServiceStatus.State.PENDING,
                "Starting",
                "Starting the loopback MCP server."
        ));
        try {
            startMcpServer();
            updateMcpStatus(new ServiceStatus(
                    ServiceStatus.State.AVAILABLE,
                    "Listening",
                    "MCP is listening at " + mcpServer.endpointUrl()
            ));
        } catch (Exception exception) {
            updateMcpStatus(new ServiceStatus(
                    ServiceStatus.State.FAILED,
                    "Unavailable",
                    "MCP startup failed: " + exception
            ));
            System.err.println("TotalDebug Companion MCP is unavailable: " + exception.getMessage());
            exception.printStackTrace(System.err);
        }
    }

    private static void closeMcpServer() {
        CompanionMcpServer server = mcpServer;
        mcpServer = null;
        if (server != null) {
            server.close();
        }
        updateMcpStatus(new ServiceStatus(
                ServiceStatus.State.INACTIVE,
                "Stopped",
                "The MCP server is stopped."
        ));
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

    private static void updateGameStatus(ServiceStatus status) {
        if (!uiStarted) {
            return;
        }
        SwingUtilities.invokeLater(() -> MainWindow.INSTANCE.setGameStatus(status));
    }

    private static void updateMcpStatus(ServiceStatus status) {
        if (!uiStarted) {
            return;
        }
        SwingUtilities.invokeLater(() -> MainWindow.INSTANCE.setMcpStatus(status));
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
        if (uiStarted) {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(CompanionApp::exit);
                return;
            }
            if (!MainWindow.INSTANCE.getEditorTabs().canCloseAll()) {
                return;
            }
            try (var state = RuntimePhase.start("close.request-save")) {
                GlobalConfig.getInstance().saveNow();
                instanceState.saveNow();
            } catch (IOException exception) {
                JOptionPane.showMessageDialog(MainWindow.INSTANCE, exception.getMessage(),
                        "Unable to save state", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        EXIT.countDown();
    }

    public static boolean isConnected() {
        return session != null && session.isConnected();
    }

    public static boolean hasProfile() {
        return profile != null;
    }

    public static String getActiveRuntimeSignature() {
        return activeRuntimeSignature;
    }

    public static boolean send(AbstractMessage message) {
        CompanionSession current = session;
        return current != null && current.send(message);
    }

    public static void openClass(String binaryName, int targetType, String targetIdentifier) {
        openOrQueue(
                NavigationTargets.fromClassOpen(binaryName, targetType, targetIdentifier),
                NavigationService.Activation.ACTIVATE_WINDOW
        );
    }

    private static void openOrQueue(NavigationTarget target, NavigationService.Activation activation) {
        if (decompilationService == null) {
            synchronized (CompanionApp.class) {
                if (decompilationService == null) {
                    pendingNavigations.add(new PendingNavigation(target, activation));
                    return;
                }
            }
        }
        MainWindow.INSTANCE.navigation().navigate(target, activation);
    }

    public static void openDebugFrame(
            com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine.StackFrame frame,
            boolean activateEditor
    ) {
        if (frame.binaryName().isBlank() || frame.line() < 1) {
            return;
        }
        openOrQueue(
                new NavigationTarget.RuntimeLine(frame.binaryName(), frame.line()),
                activateEditor
                        ? NavigationService.Activation.ACTIVATE_WINDOW
                        : NavigationService.Activation.KEEP_CURRENT_WINDOW
        );
    }

    private static DebugEngine.Source loadDebugSource(String binaryName) throws IOException {
        CompanionDecompilationService service = decompilationService;
        return service == null ? null : service.loadDebugSource(binaryName);
    }

    public static InstancePaths instancePaths() {
        return new InstancePaths(requireProfile().dataDirectory());
    }

    public static InstanceState instanceState() {
        return instanceState;
    }

    public static Path getRootPath() {
        return requireProfile().dataDirectory();
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

    public static RuntimeSourceCatalog getRuntimeSourceCatalog() {
        return runtimeSourceCatalog;
    }

    public static CompanionDecompilationService getDecompilationService() {
        CompanionDecompilationService service = decompilationService;
        if (service == null) {
            throw new IllegalStateException("Decompilation is unavailable");
        }
        return service;
    }

    public static DebuggerSessionController getDebuggerController() {
        DebuggerSessionController controller = debuggerController;
        if (controller == null) {
            throw new IllegalStateException("Debugger controller is not initialized");
        }
        return controller;
    }

    public static boolean isDebuggerConnected() {
        DebuggerSessionController controller = debuggerController;
        if (controller == null) {
            return false;
        }
        DebuggerSessionController.Phase phase = controller.status().phase();
        return phase == DebuggerSessionController.Phase.RUNNING
                || phase == DebuggerSessionController.Phase.PAUSED;
    }

    private static DebuggerSessionController createDebuggerController() {
        DebuggerSessionController controller = new DebuggerSessionController(CompanionApp::loadDebugSource, () -> evaluationClasspath, CompanionApp::loadBreakpointScript);
        controller.setBreakpointsMuted(instanceState().debuggerBreakpointsMuted()).join();
        controller.addListener(new DebuggerSessionController.Listener() {
            @Override
            public void breakpointsChanged(
                    URI sourceUri,
                    List<DebuggerSessionController.Breakpoint> breakpoints
            ) {
                persistBreakpoints(controller);
            }

            @Override
            public void breakpointsMutedChanged(boolean muted) {
                instanceState().setDebuggerBreakpointsMuted(muted);
            }
        });
        return controller;
    }

    private static String loadBreakpointScript(String name) {
        Path relative = Path.of(name);
        Path root = instancePaths().scripts().toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !file.startsWith(root) || file.equals(root)) {
            throw new IllegalArgumentException("Breakpoint script must be relative to the scripts directory");
        }
        try { return java.nio.file.Files.readString(file); }
        catch (IOException failure) { throw new IllegalStateException("Unable to read breakpoint script " + name, failure); }
    }

    private static List<DebuggerSessionController.BreakpointDefinition> restoreBreakpoints(String runtimeSignature) {
        return instanceState().debuggerBreakpoints(runtimeSignature).stream()
                .map(persisted -> {
                    DebugEngine.MethodTarget method = persisted.methodOwner() == null
                            ? null
                            : new DebugEngine.MethodTarget(
                                    persisted.methodOwner(),
                                    persisted.methodName(),
                                    persisted.methodDescriptor()
                            );
                    DebugEngine.SourceBreakpoint request = new DebugEngine.SourceBreakpoint(
                            persisted.line(),
                            persisted.debuggerLine(),
                            method,
                            persisted.condition(),
                            persisted.hitCondition(), persisted.action()
                    );
                    return new DebuggerSessionController.BreakpointDefinition(
                            URI.create(persisted.sourceUri()),
                            persisted.binaryName(),
                            request,
                            persisted.enabled()
                    );
                })
                .toList();
    }

    private static void persistBreakpoints(DebuggerSessionController controller) {
        String runtimeSignature = activeRuntimeSignature;
        if (runtimeSignature == null || runtimeSignature.isBlank()) {
            return;
        }
        List<InstanceState.PersistedBreakpoint> persisted = controller.breakpointDefinitions().stream()
                .map(definition -> {
                    DebugEngine.SourceBreakpoint request = definition.request();
                    DebugEngine.MethodTarget method = request.method();
                    return new InstanceState.PersistedBreakpoint(
                            definition.sourceUri().toString(),
                            definition.binaryName(),
                            request.line(),
                            request.debuggerLine(),
                            method == null ? null : method.ownerClassName(),
                            method == null ? null : method.name(),
                            method == null ? null : method.descriptor(),
                            request.condition(),
                            request.hitCondition(),
                            definition.enabled(), request.action()
                    );
                })
                .toList();
        instanceState().setDebuggerBreakpoints(runtimeSignature, persisted);
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

    private static void invalidateCodeInsightService() {
        CodeInsightService service = codeInsightService;
        if (service != null) {
            service.rebind(
                    () -> {
                        throw new IllegalStateException("Runtime class index is not ready");
                    },
                    RuntimeSourceCatalog.empty()
            );
        }
    }

    private static void closeDecompilationService() {
        evaluationClasspath = null;
        CompanionDecompilationService service = decompilationService;
        decompilationService = null;
        if (service != null) {
            service.close();
            if (uiStarted) {
                MainWindow.INSTANCE.navigation().runtimeChanged();
            }
        }
    }

    private static String newInstanceToken() {
        byte[] token = new byte[32];
        TOKEN_RANDOM.nextBytes(token);
        return HexFormat.of().formatHex(token);
    }

    private static void writeSecret(Path keyFile, String token) throws IOException {
        AtomicFiles.writeSecret(keyFile, token);
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
