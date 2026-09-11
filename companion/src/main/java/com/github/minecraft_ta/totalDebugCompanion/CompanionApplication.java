package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totaldebug.storage.AppPaths;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope.PendingNavigation;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.RuntimePhase;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JDTHacks;
import com.github.minecraft_ta.totalDebugCompanion.ui.CompanionUi;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.decompile.CompanionDecompilationService;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugTargetDescriptor;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.mcp.CodeModeJobService;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptCompilationService;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptExecutionService;
import com.github.minecraft_ta.totaldebug.protocol.scnet.OpenClassMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.StopScriptMessage;
import com.github.minecraft_ta.totalDebugCompanion.mcp.CompanionMcpServer;
import com.github.minecraft_ta.totaldebug.protocol.scnet.DebugTargetMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RuntimeInventoryMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerManifestMessage;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTargets;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.ProjectRegistry;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.tth05.scnet.message.AbstractMessage;
import org.eclipse.jdt.core.dom.ASTParser;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.LinkedHashMap;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

public final class CompanionApplication implements AutoCloseable {
    private final CountDownLatch exitRequested = new CountDownLatch(1);

    private ScriptExecutionService scriptExecutions;
    private CompanionSession session;
    private final CompanionLaunchConfiguration launchConfiguration;
    private final Object lifecycleLock = new Object();
    private volatile ProjectScope current;
    private final InstanceState emptyState = InstanceState.inMemory();
    private final CodeInsightService codeInsightService = new CodeInsightService(
            () -> { throw new IllegalStateException("Runtime class index is not ready"); }, RuntimeSourceCatalog.empty());
    private RuntimeIndexService runtimeIndexService;
    private final ScriptCompilationService scriptCompiler = new ScriptCompilationService(this::send, this::send);
    private CompanionMcpServer mcpServer;
    private volatile DebuggerSessionController debuggerController;
    private volatile CompanionUi ui;
    private ServiceStatus gameStatus;
    private ServiceStatus mcpStatus;
    private volatile boolean closed;
    private ProjectRegistry projects;
    private volatile boolean switching;
    private final ExecutorService projectWorker = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform().daemon().name("companion-projects").unstarted(runnable));

    public CompanionApplication(CompanionLaunchConfiguration configuration, String token) throws IOException {
        this(configuration, token, null);
    }

    public CompanionApplication(CompanionLaunchConfiguration configuration, String token, CompanionUi ui) throws IOException {
        launchConfiguration = Objects.requireNonNull(configuration);
        this.ui = ui;
        try {
            JDTHacks.init(configuration.paths().jdtCache());
            runtimeIndexService = new RuntimeIndexService(lifecycleLock, this::installRuntimeSnapshot);
            runtimeIndexService.addStatusListener(this::updateRuntimeIndexUi);
            debuggerController = createDebuggerController();
            restoreProfile();
            session = new CompanionSession(token, this::attachSelectedProfile, new CompanionSession.Listener() {
                @Override public void openClass(OpenClassMessage message) {
                    CompanionApplication.this.openClass(message.binaryName(), message.targetType(), message.targetIdentifier());
                }
                @Override public void focusWindow() { CompanionApplication.this.focusWindow(); }
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
                    scriptCompiler.runtimeDisconnected();
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
                public void serverManifest(ServerManifestMessage message) {
                    scriptCompiler.acceptServerManifest(message);
                }
    
                @Override
                public void debugTarget(DebugTargetMessage message) {
                    handleDebugTarget(message);
                }
            });
            scriptExecutions = new ScriptExecutionService(session, scriptCompiler, this::isConnected);
            session.setProjectSelectionHandler(hello -> {
                try { openProject(CompanionProfile.fromHello(hello)).join(); }
                catch (CompletionException failure) {
                    throw new IOException(failure.getCause().getMessage(), failure.getCause());
                }
            });
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public void start() throws IOException {
        updateGameStatus(new ServiceStatus(ServiceStatus.State.INACTIVE, "Offline", "Minecraft is not connected."));
        session.bindAndPublish(launchConfiguration);
        startOptionalMcpServer();
    }

    public void awaitExit() throws InterruptedException { exitRequested.await(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try (var shutdown = RuntimePhase.start("companion.shutdown")) {
            projectWorker.close();
            synchronized (lifecycleLock) {
                if (current != null && current.isActive()) current.beginSwitch();
                switching = true;
            }
            if (runtimeIndexService != null) runCleanup("Close runtime loader", runtimeIndexService::close);
            runCleanup("Close MCP", this::closeMcpServer);
            if (session != null) runCleanup("Close session", session::close);
            if (debuggerController != null) runCleanup("Close debugger", debuggerController::close);
            CompanionUi view = ui;
            ui = null;
            if (view != null) {
                try { onEdtAndWait(view::dispose); }
                catch (InvocationTargetException | InterruptedException failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    reportCleanupFailure("Close UI", failure);
                }
            }
            ProjectScope scope;
            synchronized (lifecycleLock) {
                scope = current;
                if (scope != null) scope.retire();
                current = null;
            }
            if (scope != null) {
                if (scope.runtime() != null) CompanionClassIndex.clear();
                try { scope.close(); }
                catch (IOException | RuntimeException failure) { reportCleanupFailure("Close project", failure); }
            }
            runCleanup("Close code insight", codeInsightService::close);
            runCleanup("Close script compiler", scriptCompiler::close);
            try { GlobalConfig.getInstance().saveNow(); emptyState.close(); }
            catch (IOException failure) { reportCleanupFailure("Save application state", failure); }
        }
    }

    private void attachSelectedProfile(
            ClientHelloMessage hello
    ) throws IOException {
        synchronized (lifecycleLock) {
            CompanionProfile requested;
            try {
                requested = CompanionProfile.fromHello(hello);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid Minecraft profile", exception);
            }
            if (switching || !requested.equals(currentProject())) {
                throw new IOException("Select this project explicitly before connecting");
            }
        }
    }

    private void handleDebugTarget(DebugTargetMessage message) {
        if (switching) return;
        if (message.targetKind() != DebugTargetMessage.LOCAL_JVM) {
            throw new IllegalArgumentException("Unknown debug target kind: " + message.targetKind());
        }
        debuggerController.acceptTarget(new DebugTargetDescriptor(
                message.targetId(),
                message.displayName(),
                message.processId()
        ));
    }

    private void restoreProfile() throws IOException {
        projects = ProjectRegistry.open(launchConfiguration.paths());
        CompanionProfile selected = projects.selected();
        if (selected != null) {
            try { activateProfile(selected); }
            catch (IOException failure) { System.err.println("Unable to reopen selected project: " + failure.getMessage()); }
        }
    }

    public List<ProjectRegistry.Project> projects() {
        return projects == null ? List.of() : projects.projects();
    }

    public CompanionProfile currentProject() { var scope = current; return scope == null ? null : scope.profile(); }

    public boolean isSwitching() { return switching; }

    public ProjectScope currentScope() { return current; }

    public ProjectScope requireProject() {
        if (closed) throw new IllegalStateException("Application is closed");
        ProjectScope scope = current;
        if (scope == null) throw new IllegalStateException("No Minecraft project is loaded");
        scope.requireActive();
        return scope;
    }

    /** Application API; selection controls and MCP project tools are added separately. */
    public CompletableFuture<Void> openProject(CompanionProfile requested) {
        Objects.requireNonNull(requested);
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Application is closed"));
        return CompletableFuture.runAsync(() -> {
            try { switchProject(requested); }
            catch (IOException failure) { throw new CompletionException(failure); }
        }, projectWorker);
    }

    private void switchProject(CompanionProfile requested) throws IOException {
        validateProfile(requested);
        if (requested.equals(currentProject())) { projects.select(requested); return; }
        // Prepare the actual replacement before disturbing the current project.
        ProjectScope replacement = ProjectScope.open(lifecycleLock, requested);
        replacement.beginSwitch();
        ProjectScope old;
        synchronized (lifecycleLock) {
            old = current;
            switching = true;
            if (old != null) old.beginSwitch();
        }
        boolean installed = false;
        try {
            if (ui != null) {
                boolean[] canSwitch = {false};
                SwingUtilities.invokeAndWait(() -> canSwitch[0] = ui.prepareProjectSwitch());
                if (!canSwitch[0]) throw new IOException("Project switch cancelled because an editor could not be saved");
            }
            if (old != null) old.state().saveNow();
            if (ui != null) {
                boolean[] viewsClosed = {false};
                SwingUtilities.invokeAndWait(() -> viewsClosed[0] = ui.closeProjectViews());
                if (!viewsClosed[0]) throw new IOException("Project switch cancelled because an editor could not be closed");
            }
            synchronized (lifecycleLock) {
                if (old != null) old.retire();
                current = null;
                if (runtimeIndexService != null) runtimeIndexService.clear();
            }
            // Retirement is terminal. Attempt every detach and install the prepared replacement even if
            // a broken debugger/connection cannot detach cleanly.
            if (mcpServer != null) runCleanup("Disconnect execution jobs", mcpServer::prepareProjectSwitch);
            runCleanup("Disconnect script compiler", scriptCompiler::runtimeDisconnected);
            if (session != null) runCleanup("Disconnect Minecraft", session::disconnect);
            runCleanup("Clear debugger target", () -> getDebuggerController().clearTarget().join());
            runCleanup("Clear debugger breakpoints", () -> getDebuggerController().replaceBreakpointDefinitions(List.of()).join());
            CompanionClassIndex.clear();
            if (old != null) {
                try { old.close(); }
                catch (IOException | RuntimeException failure) { reportCleanupFailure("Close retired project", failure); }
            }
            ASTCache.clear();
            synchronized (lifecycleLock) { current = replacement; }
            installed = true;
            runCleanup("Restore debugger preferences", () -> restoreProjectState(replacement));
            if (ui != null) refreshUiProfile();
            updateGameStatus(new ServiceStatus(ServiceStatus.State.INACTIVE, "Offline", "Selected project is not connected to Minecraft."));
            try { projects.select(requested); }
            catch (IOException failure) {
                throw new IOException("Project opened, but its selection could not be saved: " + failure.getMessage(), failure);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Project switch interrupted", failure);
        } catch (InvocationTargetException failure) {
            throw new IOException("Unable to close project editors", failure.getCause());
        } finally {
            try {
                if (!installed) { replacement.retire(); replacement.close(); }
            } finally {
                synchronized (lifecycleLock) {
                    if (old != null) old.cancelSwitch();
                    try {
                        if (installed && runtimeIndexService != null) runtimeIndexService.restore(requested.dataDirectory());
                    } finally {
                        if (installed) replacement.cancelSwitch();
                        switching = false;
                    }
                }
                onUi(view -> view.setSwitching(isSwitching()));
            }
        }
    }

    private void runCleanup(String description, Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) { reportCleanupFailure(description, failure); }
    }

    private void reportCleanupFailure(String description, Exception failure) {
        System.getLogger(CompanionApplication.class.getName()).log(System.Logger.Level.WARNING,
                description + " failed", failure);
    }

    private void activateProfile(CompanionProfile requested) throws IOException {
        validateProfile(requested);
        ProjectScope replacement = ProjectScope.open(lifecycleLock, requested);
        synchronized (lifecycleLock) { current = replacement; }
        restoreProjectState(replacement);
        if (runtimeIndexService != null) runtimeIndexService.restore(requested.dataDirectory());
    }

    private void restoreProjectState(ProjectScope scope) {
        getDebuggerController().setBreakpointsMuted(scope.state().debuggerBreakpointsMuted()).join();
        getDebuggerController().setExceptionBreakpoints(scope.state().breakOnCaughtExceptions(),
                scope.state().breakOnUncaughtExceptions()).join();
    }

    private void validateProfile(CompanionProfile requested) throws IOException {
        if (!Files.isDirectory(requested.workspaceDirectory())) {
            throw new IOException("Minecraft workspace not found");
        }
        Files.createDirectories(requested.dataDirectory());
        setupDataDirectories(requested.dataDirectory());
    }

    private void handleRuntimeInventory(RuntimeInventoryMessage message) {
        synchronized (lifecycleLock) {
            if (switching) return;
            CompanionProfile current = currentProject();
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
    }

    private void installRuntimeSnapshot(RuntimeIndexService.ReadySnapshot snapshot) {
        installRuntimeSnapshot(snapshot, RuntimeSnapshotBytecodeSource.fromRuntime(snapshot.sources(), snapshot.index(),
                snapshot.indexFile().getParent().resolve("inventory.json"), snapshot.inventoryId()));
    }

    void installRuntimeSnapshot(RuntimeIndexService.ReadySnapshot snapshot,
                                                             RuntimeSnapshotBytecodeSource bytecodeSource) {
        synchronized (lifecycleLock) {
            if (switching) throw new IllegalStateException("Project is switching");
            ProjectScope scope = requireProject();
            CompanionProfile current = scope.profile();
            RuntimeBinding replacement;
            try {
                replacement = new RuntimeBinding(snapshot, current.dataDirectory(), bytecodeSource, scriptCompiler, codeInsightService);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to prepare the runtime class index", exception);
            }
            try {
                closeRuntime();
                replacement.attach();
                // Queue before publication: rejected scheduling still leaves ownership with the loader.
                // The follow-up acquires this lock after the loader finishes its installation callback.
                projectWorker.execute(() -> finishRuntimeInstallation(replacement, scope));
                CompanionClassIndex.set(snapshot.index());
                scope.bindRuntime(replacement);
                replacement.acceptOwnership();
            } catch (RuntimeException failure) {
                replacement.close();
                throw failure;
            }
        }
    }

    private void finishRuntimeInstallation(RuntimeBinding installed, ProjectScope selected) {
        try {
            CompletableFuture<?> breakpoints;
            synchronized (lifecycleLock) {
                if (!selected.isActive() || selected.runtime() != installed || current != selected) return;
                breakpoints = getDebuggerController().replaceBreakpointDefinitions(
                        selected.restoreBreakpoints(installed.snapshot().signature()));
            }
            // A failed debugger/UI refresh must never return ownership of an installed index to its loader.
            breakpoints.join();
            prewarmJavaParser();
        } catch (RuntimeException failure) {
            System.getLogger(CompanionApplication.class.getName()).log(System.Logger.Level.WARNING,
                    "Runtime installed, but debugger refresh failed", failure);
        } finally {
            CompanionUi view = ui;
            if (view != null) SwingUtilities.invokeLater(() -> {
                if (ui != view || !selected.isActive() || selected.runtime() != installed || current != selected) return;
                view.runtimeChanged();
                List<PendingNavigation> queued;
                synchronized (lifecycleLock) {
                    if (!selected.isActive() || selected.runtime() != installed || current != selected) return;
                    queued = selected.drainNavigations();
                }
                for (PendingNavigation pending : queued) {
                    view.navigate(pending.target(), pending.activation());
                }
            });
        }
    }

    static void setupDataDirectories(Path rootPath) throws IOException {

        Files.createDirectories(new InstancePaths(rootPath).scripts());
    }

    private void prewarmJavaParser() {
        if (!CompanionClassIndex.isOpen()) {
            return;
        }
        // This follow-up already runs on the project worker, which shutdown drains before closing the index.
        ASTParser parser = JdtConfiguration.createParser();
        parser.setSource(new CompilationUnitImpl("Test", "class Test{}"));
        parser.setResolveBindings(true);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.createAST(null);
    }

    private void startMcpServer() throws Exception {
        var jobs = new CodeModeJobService(session, scriptExecutions, this::requireProject,
                this::isConnected, this::runtimeContext);
        startMcpServer(jobs, CompanionMcpServer.MCP_PORT);
    }

    CompanionMcpServer startMcpServer(CodeModeJobService jobs, int port) throws Exception {
        CompanionMcpServer server = new CompanionMcpServer(this, jobs, port);
        try {
            server.start();
            mcpServer = server;
            updateMcpStatus(new ServiceStatus(ServiceStatus.State.AVAILABLE, "Listening",
                    "MCP is listening at " + server.endpointUrl()));
            System.err.println("TotalDebug Companion MCP listening at " + server.endpointUrl());
            return server;
        } catch (Exception failure) { server.close(); throw failure; }
    }

    private void startOptionalMcpServer() {
        updateMcpStatus(new ServiceStatus(
                ServiceStatus.State.PENDING,
                "Starting",
                "Starting the loopback MCP server."
        ));
        try {
            startMcpServer();

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

    private void closeMcpServer() {
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

    private Map<String, Object> runtimeContext() {
        CompanionProfile current = currentProject();
        if (current == null) {
            return Map.of();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("profile_id", current.id());
        context.put("workspace_directory", current.workspaceDirectory().toString());
        RuntimeBinding installed = currentRuntime();
        if (installed != null) {
            context.put("runtime_signature", installed.snapshot().signature());
            context.put("index_file", installed.snapshot().indexFile().toString());
        }
        return Map.copyOf(context);
    }

    public MainWindow createWindow() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Create the window on the EDT");
        synchronized (lifecycleLock) { checkWindowCreation(); }
        MainWindow window = new MainWindow(this::currentScope, getDebuggerController(), codeInsightService,
                scriptExecutions, session, runtimeIndexService, this::openDebugFrame, this::exit);
        List<PendingNavigation> queued;
        try {
            synchronized (lifecycleLock) {
                checkWindowCreation();
                // These direct EDT updates and the replay snapshot precede publication.
                window.refreshProfile();
                window.setRuntimeIndexStatus(getRuntimeIndexStatus());
                if (gameStatus != null) window.setGameStatus(gameStatus);
                if (mcpStatus != null) window.setMcpStatus(mcpStatus);
                ProjectScope scope = current;
                queued = scope != null && scope.runtime() != null ? scope.drainNavigations() : List.of();
                ui = window;
            }
        } catch (RuntimeException | Error failure) {
            window.dispose();
            throw failure;
        }
        for (PendingNavigation pending : queued) window.navigate(pending.target(), pending.activation());
        return window;
    }

    private void checkWindowCreation() {
        if (closed) throw new IllegalStateException("Application is closed");
        if (ui != null) throw new IllegalStateException("Application already has a UI");
        if (switching || current != null && !current.isActive()) throw new IllegalStateException("Project is switching");
    }

    private void refreshUiProfile() {
        CompanionUi view = ui;
        if (view == null) return;
        try { onEdtAndWait(view::refreshProfile); }
        catch (InvocationTargetException failure) { throw new IllegalStateException("Unable to refresh the Companion UI", failure.getCause()); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted refreshing the Companion UI", failure); }
    }

    private void onUi(Consumer<CompanionUi> action) {
        CompanionUi view = ui;
        if (view != null) SwingUtilities.invokeLater(() -> {
            if (!closed && ui == view) action.accept(view);
        });
    }
    private void updateGameStatus(ServiceStatus status) {
        synchronized (lifecycleLock) {
            gameStatus = status;
            onUi(view -> view.setGameStatus(status));
        }
    }
    private void updateMcpStatus(ServiceStatus status) {
        synchronized (lifecycleLock) {
            mcpStatus = status;
            onUi(view -> view.setMcpStatus(status));
        }
    }
    private void updateRuntimeIndexUi(RuntimeIndexService.Status status) { onUi(view -> view.setRuntimeIndexStatus(status)); }
    public void focusWindow() { onUi(CompanionUi::focus); }

    public void exit() {
        CompanionUi view = ui;
        if (view != null) {
            if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::exit); return; }
            if (!view.canExit()) return;
            try { GlobalConfig.getInstance().saveNow(); instanceState().saveNow(); }
            catch (IOException failure) { view.showError("Unable to save state", failure.getMessage()); return; }
        }
        exitRequested.countDown();
    }

    private static void onEdtAndWait(Runnable action) throws InvocationTargetException, InterruptedException {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeAndWait(action);
    }

    public boolean isConnected() {
        return !closed && !switching && session != null && session.isConnected();
    }

    private boolean send(AbstractMessage message) {
        if (switching && !(message instanceof StopScriptMessage)) return false;
        CompanionSession current = session;
        return current != null && current.send(message);
    }

    CompanionSession session() { return session; }

    public void openClass(String binaryName, int targetType, String targetIdentifier) {
        openOrQueue(
                NavigationTargets.fromClassOpen(binaryName, targetType, targetIdentifier),
                NavigationService.Activation.ACTIVATE_WINDOW
        );
    }

    private void openOrQueue(NavigationTarget target, NavigationService.Activation activation) {
        CompanionUi view;
        ProjectScope scope;
        RuntimeBinding installed;
        synchronized (lifecycleLock) {
            scope = current;
            if (closed || switching || scope == null || !scope.isActive()) return;
            view = ui;
            installed = scope.runtime();
            if (view == null || installed == null) { scope.queueNavigation(target, activation); return; }
        }
        SwingUtilities.invokeLater(() -> {
            if (ui == view && current == scope && scope.isActive() && scope.runtime() == installed) view.navigate(target, activation);
        });
    }

    public void openDebugFrame(
            DebugEngine.StackFrame frame,
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

    private DebugEngine.Source loadDebugSource(String binaryName) throws IOException {
        RuntimeBinding current = currentRuntime();
        CompanionDecompilationService service = current == null ? null : current.decompiler();
        return service == null ? null : service.loadDebugSource(binaryName);
    }

    public AppPaths appPaths() {
        return launchConfiguration.paths();
    }

    InstancePaths instancePaths() {
        return new InstancePaths(requireProfile().dataDirectory());
    }

    public InstanceState instanceState() {
        ProjectScope scope = current;
        return scope == null ? emptyState : scope.state();
    }

    CompanionDecompilationService getDecompilationService() {
        RuntimeBinding current = currentRuntime();
        CompanionDecompilationService service = current == null ? null : current.decompiler();
        if (service == null) {
            throw new IllegalStateException("Decompilation is unavailable");
        }
        return service;
    }

    public DebuggerSessionController getDebuggerController() {
        DebuggerSessionController controller = debuggerController;
        if (controller == null) {
            throw new IllegalStateException("Debugger controller is not initialized");
        }
        return controller;
    }

    private DebuggerSessionController createDebuggerController() {
        DebuggerSessionController controller = new DebuggerSessionController(this::loadDebugSource, () -> { RuntimeBinding current = currentRuntime(); return current == null ? null : current.classpath(); }, name -> requireProject().loadBreakpointScript(name));
        controller.setBreakpointsMuted(instanceState().debuggerBreakpointsMuted()).join();
        controller.addListener(new DebuggerSessionController.Listener() {
            @Override
            public void breakpointsChanged(
                    URI sourceUri,
                    List<DebuggerSessionController.Breakpoint> breakpoints
            ) {
                ProjectScope scope = current;
                if (scope != null) scope.persistBreakpoints(controller);
            }

            @Override
            public void breakpointsMutedChanged(boolean muted) {
                ProjectScope scope = current;
                if (scope != null && scope.isActive()) scope.state().setDebuggerBreakpointsMuted(muted);
            }
        });
        return controller;
    }

    public RuntimeIndexService.Status getRuntimeIndexStatus() {
        RuntimeIndexService service = runtimeIndexService;
        return service == null
                ? new RuntimeIndexService.Status(RuntimeIndexService.Phase.WAITING, "Waiting for runtime inventory", null)
                : service.status();
    }

    private CompanionProfile requireProfile() {
        CompanionProfile current = currentProject();
        if (current == null) {
            throw new IllegalStateException("No Minecraft profile is loaded");
        }
        return current;
    }

    public RuntimeBinding currentRuntime() {
        ProjectScope scope = current;
        return scope == null ? null : scope.runtime();
    }

    private void closeRuntime() {
        ProjectScope scope = current;
        CompanionClassIndex.clear();
        if (scope != null) scope.closeRuntime();
    }

}
