package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Severity;
import com.github.minecraft_ta.totalDebugCompanion.script.EditorScriptRunService;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionRuns;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectControls;
import com.github.minecraft_ta.totalDebugCompanion.runtime.IndexIdentity;
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
import com.github.minecraft_ta.totaldebug.protocol.scnet.InspectSubjectMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.OpenClassMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.PackCatalogMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ResourceSnapshotMessage;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totaldebug.protocol.scnet.StopScriptMessage;
import com.github.minecraft_ta.totalDebugCompanion.mcp.CompanionMcpServer;
import com.github.minecraft_ta.totaldebug.protocol.scnet.DebugTargetMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RuntimeInventoryMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RetryRuntimeInventoryMessage;
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
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.LinkedHashMap;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class CompanionApplication implements AutoCloseable, ProjectControls {
    private final CountDownLatch exitRequested = new CountDownLatch(1);

    private final NotificationCenter notifications = new NotificationCenter();
    private final ExecutionRuns executionRuns;
    private final EditorScriptRunService editorRuns;
    public NotificationCenter notifications() { return notifications; }
    private final ScriptExecutionService scriptExecutions;
    private final CompanionSession session;
    private final CompanionLaunchConfiguration launchConfiguration;
    private final Object lifecycleLock = new Object();
    private volatile ProjectScope current;
    private final InstanceState emptyState = InstanceState.inMemory();
    private final CodeInsightService codeInsightService = new CodeInsightService(
            () -> { throw new IllegalStateException("Runtime class index is not ready"); }, RuntimeSourceCatalog.empty());
    private final RuntimeIndexService runtimeIndexService;
    private final ScriptCompilationService scriptCompiler = new ScriptCompilationService(this::send, this::send);
    private final ItemIconService itemIcons = new ItemIconService();
    private volatile CompanionMcpServer mcpServer;
    // Job tracking must survive HTTP shutdown so project retirement can still cancel submitted code.
    private volatile CodeModeJobService mcpJobs;
    private final DebuggerSessionController debuggerController;
    private volatile CompanionUi ui;
    private ServiceStatus gameStatus;
    private ServiceStatus mcpStatus;
    private volatile boolean closed;
    private ProjectRegistry projects;
    private volatile boolean switching;
    private static final class Reconnect {
        final ProjectScope project;
        final CompletableFuture<Void> result = new CompletableFuture<>();
        boolean resetComplete;

        Reconnect(ProjectScope project) { this.project = project; }
    }
    private volatile Reconnect reconnect;
    private static final class Launch {
        final ProjectScope project;
        final CompletableFuture<Void> result = new CompletableFuture<>();
        boolean dispatched;

        Launch(ProjectScope project) { this.project = project; }
    }
    private volatile Launch launch;
    private final PrismGameLauncher gameLauncher;
    private final ExecutorService projectWorker = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform().daemon().name("companion-projects").unstarted(runnable));
    // HTTP shutdown may await handlers using projectWorker; it cannot run on that worker.
    private final ExecutorService mcpWorker = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform().daemon().name("companion-mcp-lifecycle").unstarted(runnable));

    public CompanionApplication(CompanionLaunchConfiguration configuration, String token) throws IOException {
        this(configuration, token, null);
    }

    public CompanionApplication(CompanionLaunchConfiguration configuration, String token, CompanionUi ui) throws IOException {
        this(configuration, token, ui, new PrismGameLauncher());
    }

    CompanionApplication(CompanionLaunchConfiguration configuration, String token, CompanionUi ui, PrismGameLauncher gameLauncher) throws IOException {
        this.gameLauncher = Objects.requireNonNull(gameLauncher);
        launchConfiguration = Objects.requireNonNull(configuration);
        this.ui = ui;
        try {
            JDTHacks.init(configuration.paths().jdtCache());
            runtimeIndexService = new RuntimeIndexService(lifecycleLock, this::installRuntimeSnapshot);
            runtimeIndexService.addStatusListener(this::updateRuntimeIndexUi);
            debuggerController = createDebuggerController();
            debuggerController.addListener(new DebuggerSessionController.Listener() {
                private Throwable lastFailure;
                @Override public void statusChanged(DebuggerSessionController.Status status) {
                    if (!closed && !switching && status.failure() != null && status.failure() != lastFailure) {
                        notifications.publish(Severity.ERROR, "Debugger operation failed", status.detail() + "\n" + status.failure(),
                                Source.capture(current, "Debugger", null));
                    }
                    lastFailure = status.failure();
                }
            });
            restoreProfile();
            session = new CompanionSession(token, this::attachSelectedProfile, new CompanionSession.Listener() {
                @Override public void openClass(OpenClassMessage message) {
                    CompanionApplication.this.openClass(message.binaryName(), message.targetType(), message.targetIdentifier());
                }
                @Override public void resourceSnapshot(ResourceSnapshotMessage message) {
                    itemIcons.accept(message.archive(), message.layers());
                }
                @Override public void packCatalog(PackCatalogMessage message) {
                    handlePackCatalog(message);
                }
                @Override public void inspectSubject(InspectSubjectMessage message) {
                    openOrQueue(new NavigationTarget.Inspection(message.payload()), NavigationService.Activation.ACTIVATE_WINDOW);
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
                    connectionEstablished();
                }

                @Override
                public void disconnected(long connection) {
                    synchronized (lifecycleLock) {
                        if (executionRuns != null) executionRuns.disconnected(connection,
                                closed || reconnect != null || current == null || current.phase() == ProjectScope.Phase.RETIRED);
                        scriptCompiler.runtimeDisconnected();
                        if (reconnect != null)
                            updateGameStatus(new ServiceStatus(ServiceStatus.State.PENDING, "Reconnecting", "Waiting for the selected Minecraft instance to connect."));
                        else if (launch != null)
                            updateGameStatus(new ServiceStatus(ServiceStatus.State.PENDING, "Starting", "Waiting for Minecraft to connect from Prism."));
                        else if (gameStatus == null || gameStatus.state() != ServiceStatus.State.FAILED)
                            updateGameStatus(new ServiceStatus(ServiceStatus.State.INACTIVE, "Offline", "Minecraft is not connected."));
                    }
                    debuggerController.clearTarget();
                    restoreOfflineAfterDisconnect();
                    Launch pendingLaunch = launch;
                    if (pendingLaunch != null) queueLaunch(pendingLaunch);
                }

                @Override
                public void runtimeInventory(RuntimeInventoryMessage message) {
                    handleRuntimeInventory(message);
                }

                @Override public void failed(String detail, ClientHelloMessage hello) {
                    synchronized (lifecycleLock) {
                        if (closed || switching) return;
                        if (hello != null && (current == null || !current.profile().id().equals(hello.profileId()))) return;
                        if (reconnect != null) failReconnect(reconnect, detail);
                        else if (launch != null) failLaunch(launch, detail);
                        else updateGameStatus(new ServiceStatus(ServiceStatus.State.FAILED, "Connection failed", detail));
                    }
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
            executionRuns = new ExecutionRuns(session, scriptExecutions);
            editorRuns = new EditorScriptRunService(executionRuns, notifications);
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
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Start Companion outside the event dispatch thread");
        updateGameStatus(new ServiceStatus(ServiceStatus.State.INACTIVE, "Offline", "Minecraft is not connected."));
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    session.bindAndPublish(launchConfiguration);
                    publishConnectionTarget();
                } catch (IOException failure) { throw new CompletionException(failure); }
            }, projectWorker).join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof IOException io) throw io;
            throw failure;
        }
        setMcpEnabled(true).join();
    }

    public void awaitExit() throws InterruptedException { exitRequested.await(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        cancelReconnect("Companion is closing");
        cancelLaunch("Companion is closing");
        notifications.close();
        itemIcons.close();
        if (executionRuns != null) executionRuns.close();
        if (editorRuns != null) editorRuns.close();
        try (var shutdown = RuntimePhase.start("companion.shutdown")) {
            mcpWorker.close();
            runCleanup("Close MCP", this::closeMcpServer);
            projectWorker.close();
            if (mcpJobs != null) runCleanup("Close MCP jobs", mcpJobs::close);
            synchronized (lifecycleLock) {
                if (current != null && current.isActive()) current.beginSwitch();
                switching = true;
            }
            if (runtimeIndexService != null) runCleanup("Close runtime loader", runtimeIndexService::close);
            if (session != null) runCleanup("Close session", session::close);
            if (debuggerController != null) runCleanup("Close debugger", debuggerController::close);
            CompanionUi view = ui;
            ui = null;
            if (view != null) {
                try { UIUtils.onEdtAndWait(view::dispose); }
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
            runtimeInventoryPending("Waiting for Minecraft to announce its current runtime inventory");
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

    public CompletableFuture<Void> openProject(CompanionProfile requested) {
        return openProject(requested, null);
    }

    @Override public CompletableFuture<Void> openProject(CompanionProfile requested, String nameOverride) {
        Objects.requireNonNull(requested);
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Application is closed"));
        return CompletableFuture.runAsync(() -> {
            try {
                switchProject(requested);
                if (nameOverride != null) projects.rename(requested.id(), nameOverride);
                onUi(CompanionUi::refreshProjects);
            }
            catch (IOException failure) { throw new CompletionException(failure); }
        }, projectWorker);
    }

    @Override public CompletableFuture<Void> renameProject(String id, String nameOverride) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Application is closed"));
        return CompletableFuture.runAsync(() -> {
            try { projects.rename(id, nameOverride); onUi(CompanionUi::refreshProjects); }
            catch (IOException failure) { throw new CompletionException(failure); }
        }, projectWorker);
    }

    @Override public CompletableFuture<Void> forgetProject(String id) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Application is closed"));
        return CompletableFuture.runAsync(() -> {
            try { projects.forget(id); }
            catch (IOException failure) { throw new CompletionException(failure); }
        }, projectWorker);
    }

    @Override public CompletableFuture<Void> retryIndex() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Application is closed"));
        ProjectScope selected = current;
        if (selected == null) return CompletableFuture.failedFuture(new IllegalStateException("No project is open"));
        return CompletableFuture.runAsync(() -> {
            if (runtimeIndexService.status().active()) return;
            boolean rebuild = selected.admit(() -> {
                if (runtimeIndexService.status().phase() != RuntimeIndexService.Phase.READY) return false;
                if (selected.runtime() == null) return false;
                runtimeIndexService.rebuild(selected.profile().dataDirectory(),
                        selected.runtime().snapshot().isRuntime() ? null : selected.profile().workspaceDirectory());
                return true;
            });
            if (rebuild) {
                onUi(CompanionUi::runtimeChanged);
                return;
            }
            boolean requestedRuntime = selected.admit(() -> {
                if (!isConnected() || runtimeIndexService.status().sourceKind() == IndexIdentity.Kind.LOCAL) return false;
                if (!session.send(new RetryRuntimeInventoryMessage()))
                    throw new IllegalStateException("Minecraft disconnected before runtime inventory could be requested");
                runtimeInventoryPending("Requesting runtime inventory again");
                return true;
            });
            if (requestedRuntime) return;
            try {
                if (restoreOfflineRuntime(selected)) onUi(CompanionUi::runtimeChanged);
            }
            catch (IOException failure) { throw new CompletionException(failure); }
        }, projectWorker);
    }

    @Override public CompletableFuture<String> gameLaunchUnavailableReason(ProjectScope expectedProject) {
        if (closed || expectedProject == null) return CompletableFuture.completedFuture("Select a Prism instance to launch");
        try { return CompletableFuture.supplyAsync(() -> {
            synchronized (lifecycleLock) {
                if (closed || switching || current != expectedProject || !expectedProject.isActive()) return "The selected project changed";
            }
            try { gameLauncher.target(expectedProject.profile()); return null; }
            catch (IOException failure) { return failure.getMessage(); }
        }, projectWorker); } catch (RejectedExecutionException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override public CompletableFuture<Void> launchGame(ProjectScope expectedProject) {
        Launch request;
        synchronized (lifecycleLock) {
            if (closed || switching || expectedProject == null || current != expectedProject || !expectedProject.isActive())
                return CompletableFuture.failedFuture(new IllegalStateException("The selected project changed before launch"));
            if (launch != null) return launch.result;
            if (session.hasClient() || reconnect != null)
                return CompletableFuture.failedFuture(new IllegalStateException("Minecraft is already connected or connecting"));
            request = new Launch(expectedProject);
            launch = request;
            updateGameStatus(new ServiceStatus(ServiceStatus.State.PENDING, "Starting", "Waiting for Minecraft to connect from Prism."));
        }
        queueLaunch(request);
        CompletableFuture.delayedExecutor(10, TimeUnit.MINUTES).execute(() ->
                failLaunch(request, "Minecraft has not connected after 10 minutes. Check Prism for launch or sign-in errors."));
        return request.result;
    }

    private void queueLaunch(Launch request) {
        try {
            projectWorker.execute(() -> {
                try {
                    synchronized (lifecycleLock) {
                        if (!canDispatchLaunch(request)) return;
                    }
                    List<String> command = gameLauncher.command(request.project.profile());
                    publishConnectionTarget();
                    Process process;
                    synchronized (lifecycleLock) {
                        if (!canDispatchLaunch(request)) return;
                        process = gameLauncher.start(command);
                        request.dispatched = true;
                    }
                    // A secondary Prism process exits after forwarding the request. Only the
                    // authenticated game connection completes launch successfully.
                    process.onExit().whenComplete((exited, failure) -> {
                        if (failure != null) failLaunch(request, "Unable to observe Prism: " + failure.getMessage());
                        else if (exited.exitValue() != 0) failLaunch(request, "Prism exited with code " + exited.exitValue() + ". Check Prism for launch errors.");
                    });
                } catch (IOException | RuntimeException failure) { failLaunch(request, failure.getMessage()); }
            });
        } catch (RejectedExecutionException failure) { failLaunch(request, "Companion is closing"); }
    }

    /** Called under lifecycleLock before discovery and again before the process is started. */
    private boolean canDispatchLaunch(Launch request) {
        return !closed && !switching && launch == request && current == request.project && request.project.isActive()
                && !request.dispatched && !session.hasClient();
    }

    private void failLaunch(Launch request, String detail) {
        synchronized (lifecycleLock) {
            if (launch != request) return;
            launch = null;
            String message = detail == null || detail.isBlank() ? "Unable to launch Minecraft" : detail;
            if (!closed && !switching && current == request.project && request.project.isActive()) {
                updateGameStatus(new ServiceStatus(ServiceStatus.State.FAILED, "Launch failed", message));
                notifications.publish(Severity.ERROR, "Unable to launch Minecraft", message, Source.capture(request.project, "Minecraft", null));
            }
            request.result.completeExceptionally(new IOException(message));
        }
    }

    private void cancelLaunch(String reason) {
        synchronized (lifecycleLock) {
            if (launch == null) return;
            var cancelled = launch;
            launch = null;
            if (!closed) updateGameStatus(new ServiceStatus(ServiceStatus.State.INACTIVE, "Offline", "Minecraft is not connected."));
            cancelled.result.completeExceptionally(new IOException(reason));
        }
    }

    @Override public CompletableFuture<Void> reconnectGame(ProjectScope expectedProject) {
        Reconnect request;
        synchronized (lifecycleLock) {
            if (closed || switching || expectedProject == null || current != expectedProject || !expectedProject.isActive())
                return CompletableFuture.failedFuture(new IllegalStateException("The selected project changed before reconnect"));
            if (launch != null) return CompletableFuture.failedFuture(new IllegalStateException("Minecraft is already starting"));
            if (reconnect != null) return reconnect.result;
            request = new Reconnect(expectedProject);
            reconnect = request;
            updateGameStatus(new ServiceStatus(ServiceStatus.State.PENDING, "Reconnecting", "Waiting for the selected Minecraft instance to connect."));
        }
        try {
            projectWorker.execute(() -> {
                try {
                    synchronized (lifecycleLock) {
                        if (closed || reconnect != request || current != request.project || !request.project.isActive()) return;
                    }
                    session.publishProfile(null);
                    try {
                        synchronized (lifecycleLock) {
                            if (closed || reconnect != request) return;
                        }
                        if (mcpJobs != null) mcpJobs.prepareProjectSwitch();
                        session.disconnect();
                    } finally {
                        if (!closed) publishConnectionTarget();
                    }
                    synchronized (lifecycleLock) {
                        if (reconnect != request) return;
                        request.resetComplete = true;
                        // A replacement may have authenticated while its advertisement was
                        // being published. It becomes eligible only after teardown finishes.
                        connectionEstablished();
                    }
                } catch (IOException | RuntimeException failure) { failReconnect(request, failure.getMessage()); }
            });
            CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS).execute(() ->
                    failReconnect(request, "No matching Minecraft connected within 30 seconds."));
        } catch (RejectedExecutionException failure) { failReconnect(request, "Companion is closing"); }
        return request.result;
    }

    private void connectionEstablished() {
        synchronized (lifecycleLock) {
            if (closed || switching || !session.isConnected() || reconnect != null && !reconnect.resetComplete) return;
            updateGameStatus(new ServiceStatus(ServiceStatus.State.AVAILABLE, "Connected", "Minecraft is connected and authenticated."));
            if (reconnect != null && reconnect.project == current) {
                var completed = reconnect;
                reconnect = null;
                completed.result.complete(null);
            }
            if (launch != null && launch.project == current) {
                var completed = launch;
                launch = null;
                completed.result.complete(null);
            }
        }
    }

    private void failReconnect(Reconnect request, String detail) {
        synchronized (lifecycleLock) {
            if (reconnect != request) return;
            reconnect = null;
            String message = detail == null || detail.isBlank() ? "Minecraft reconnect failed" : detail;
            if (!closed && !switching && current == request.project && request.project.isActive()) {
                updateGameStatus(session != null && session.isConnected()
                        ? new ServiceStatus(ServiceStatus.State.AVAILABLE, "Connected", "Minecraft remains connected; reconnect failed: " + message)
                        : new ServiceStatus(ServiceStatus.State.FAILED, "Connection failed", message));
                notifications.publish(Severity.ERROR, "Minecraft reconnect failed", message, Source.capture(request.project, "Minecraft", null));
            }
            request.result.completeExceptionally(new IOException(message));
        }
    }

    private void cancelReconnect(String reason) {
        synchronized (lifecycleLock) {
            if (reconnect == null) return;
            var cancelled = reconnect;
            reconnect = null;
            if (!closed) updateGameStatus(session != null && session.isConnected()
                    ? new ServiceStatus(ServiceStatus.State.AVAILABLE, "Connected", "Minecraft is connected and authenticated.")
                    : new ServiceStatus(ServiceStatus.State.INACTIVE, "Offline", "Minecraft is not connected."));
            cancelled.result.completeExceptionally(new IOException(reason));
        }
    }

    private void publishConnectionTarget() throws IOException {
        String profile;
        synchronized (lifecycleLock) {
            profile = closed || switching || current == null || !current.isActive() ? null : current.profile().id();
        }
        if (session != null) session.publishProfile(profile);
    }

    private void switchProject(CompanionProfile requested) throws IOException {
        validateProfile(requested);
        if (requested.equals(currentProject())) {
            projects.select(requested);
            publishConnectionTarget();
            if (restoreOfflineRuntime(requireProject())) onUi(CompanionUi::runtimeChanged);
            return;
        }
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
        Throwable switchFailure = null;
        try {
            cancelReconnect("Project changed during reconnect");
            cancelLaunch("Project changed during launch");
            if (session != null) session.publishProfile(null);
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
            CodeModeJobService jobs = mcpJobs;
            if (jobs != null) runCleanup("Disconnect execution jobs", jobs::prepareProjectSwitch);
            if (executionRuns != null) executionRuns.disconnectAll(true);
            runCleanup("Disconnect script compiler", scriptCompiler::runtimeDisconnected);
            if (session != null) runCleanup("Disconnect Minecraft", session::disconnect);
            runCleanup("Clear debugger target", () -> getDebuggerController().clearTarget().join());
            runCleanup("Clear debugger breakpoints", () -> getDebuggerController().replaceBreakpointDefinitions(List.of()).join());
            CompanionClassIndex.clear();
            if (old != null) {
                try { old.close(); }
                catch (IOException | RuntimeException failure) { reportCleanupFailure("Close retired project", failure); }
            }
            synchronized (lifecycleLock) { current = replacement; }
            installed = true;
            restoreCatalog(replacement);
            runCleanup("Restore debugger preferences", () -> restoreProjectState(replacement));
            if (ui != null) refreshUiProfile();
            updateGameStatus(new ServiceStatus(ServiceStatus.State.INACTIVE, "Offline", "Selected project is not connected to Minecraft."));
            try { projects.select(requested); }
            catch (IOException failure) {
                throw new IOException("Project opened, but its selection could not be saved: " + failure.getMessage(), failure);
            }
        } catch (IOException | RuntimeException failure) {
            switchFailure = failure;
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            var interrupted = new IOException("Project switch interrupted", failure);
            switchFailure = interrupted;
            throw interrupted;
        } catch (InvocationTargetException failure) {
            var rejected = new IOException("Unable to close project editors", failure.getCause());
            switchFailure = rejected;
            throw rejected;
        } finally {
            try {
                if (!installed) { replacement.retire(); replacement.close(); }
            } finally {
                synchronized (lifecycleLock) {
                    if (old != null) old.cancelSwitch();
                    try {
                        if (installed && runtimeIndexService != null) runtimeIndexService.restore(requested.dataDirectory(), requested.workspaceDirectory());
                    } finally {
                        if (installed) replacement.cancelSwitch();
                        switching = false;
                    }
                }
                onUi(view -> view.setSwitching(isSwitching()));
                try { publishConnectionTarget(); }
                catch (IOException failure) {
                    updateGameStatus(new ServiceStatus(ServiceStatus.State.FAILED, "Connection unavailable", "Unable to publish Companion endpoint: " + failure.getMessage()));
                    if (switchFailure != null) switchFailure.addSuppressed(failure);
                    else throw failure;
                }
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
        restoreCatalog(replacement);
        restoreProjectState(replacement);
        if (runtimeIndexService != null) runtimeIndexService.restore(requested.dataDirectory(), requested.workspaceDirectory());
    }

    /** Shows the project's saved pack catalog and item icons, which stay browsable without a game connection. */
    private void restoreCatalog(ProjectScope scope) {
        scope.catalog().addListener(() -> {
            if (currentScope() == scope) onUi(CompanionUi::catalogChanged);
        });
        itemIcons.setItemLookup(itemId -> scope.catalog().index().flatMap(index -> index.itemIcon(itemId)));
        // Independent tasks: unreadable icon archives must not keep the catalog from loading.
        CompletableFuture.runAsync(() -> itemIcons.restore(scope.paths().previews()));
        CompletableFuture.runAsync(scope.catalog()::restore);
    }

    private void handlePackCatalog(PackCatalogMessage message) {
        ProjectScope scope;
        synchronized (lifecycleLock) {
            if (switching) return;
            scope = current;
        }
        if (scope == null || !scope.isActive()) return;
        switch (message.state()) {
            case PackCatalogMessage.CAPTURING -> scope.catalog().capturing();
            case PackCatalogMessage.AVAILABLE -> scope.catalog().accept(message.inventoryId(),
                    Path.of(message.catalogFile()), ForkJoinPool.commonPool());
            default -> scope.catalog().failed(message.detail());
        }
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
        Path scripts = new InstancePaths(requested.dataDirectory()).scripts();
        if (Files.exists(scripts) && !Files.isDirectory(scripts)) throw new IOException("Scripts path is not a directory: " + scripts);
    }

    private void runtimeInventoryPending(String detail) {
        scriptCompiler.suspendRuntime();
        runtimeIndexService.waiting(detail);
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
                    runtimeInventoryPending(
                            message.detail().isBlank() ? "Minecraft is preparing runtime sources" : message.detail());
                }
                case RuntimeInventoryMessage.AVAILABLE -> {
                    runtimeIndexService.accept(
                            current.dataDirectory(),
                            message.inventoryId(),
                            Path.of(message.inventoryFile())
                    );
                    this.current.catalog().inventoryAnnounced(message.inventoryId());
                }
                case RuntimeInventoryMessage.FAILED -> runtimeIndexService.failedBeforeBuild(message.detail());
                default -> runtimeIndexService.failedBeforeBuild("Minecraft sent an unknown runtime inventory state");
            }
        }
    }

    private void installRuntimeSnapshot(RuntimeIndexService.ReadySnapshot snapshot) {
        installRuntimeSnapshot(snapshot, snapshot.isRuntime()
                ? RuntimeSnapshotBytecodeSource.fromRuntime(snapshot.sources(), snapshot.index(),
                snapshot.indexFile().getParent().resolve("inventory.json"), snapshot.inventoryId())
                : RuntimeSnapshotBytecodeSource.fromLocal(snapshot.sources(), snapshot.index(), snapshot.localGuard()));
    }

    void installRuntimeSnapshot(RuntimeIndexService.ReadySnapshot snapshot,
                                                             RuntimeSnapshotBytecodeSource bytecodeSource) {
        synchronized (lifecycleLock) {
            if (switching) throw new IllegalStateException("Project is switching");
            ProjectScope scope = requireProject();
            CompanionProfile current = scope.profile();
            var previous = scope.runtime();
            if (previous != null && !snapshot.rebuilt() && (previous.snapshot().localGuard() == null || previous.snapshot().localGuard().isValid())
                    && previous.snapshot().signature().equals(snapshot.signature())) {
                scriptCompiler.bind(previous.snapshot().isRuntime() ? previous.snapshot() : null);
                bytecodeSource.close();
                if (previous.snapshot() != snapshot) snapshot.close();
                return;
            }
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
                if (snapshot.localGuard() != null) snapshot.localGuard().onInvalidated(failure -> refreshChangedLocalSources(scope, snapshot));
                CompanionClassIndex.set(snapshot.index());
                scope.bindRuntime(replacement);
                replacement.acceptOwnership();
            } catch (RuntimeException failure) {
                replacement.close();
                throw failure;
            }
        }
    }

    private void refreshChangedLocalSources(ProjectScope scope, RuntimeIndexService.ReadySnapshot snapshot) {
        if (closed) return;
        try { projectWorker.execute(() -> {
            synchronized (lifecycleLock) {
                if (closed || current != scope || !scope.isActive() || scope.runtime() == null || scope.runtime().snapshot() != snapshot) return;
                closeRuntime();
            }
            try {
                restoreOfflineRuntime(scope);
            } catch (IOException failure) {
                runtimeIndexService.failedBeforeBuild("Unable to rescan mods: " + failure.getMessage());
            }
            onUi(CompanionUi::runtimeChanged);
        }); } catch (RejectedExecutionException failure) {
            if (!closed) throw failure;
        }
    }

    private boolean restoreOfflineRuntime(ProjectScope scope) throws IOException {
        if (scope.admit(session::hasClient)) return false;
        scope.refreshLocalSources();
        return scope.admit(() -> {
            // Admission can begin during filesystem discovery. Check again under the same
            // lock as live admission so no offline restore can undo compiler suspension.
            if (session.hasClient()) return false;
            runtimeIndexService.restore(scope.profile().dataDirectory(), scope.profile().workspaceDirectory());
            return true;
        });
    }

    private void restoreOfflineAfterDisconnect() {
        ProjectScope selected = current;
        if (closed || selected == null) return;
        try { projectWorker.execute(() -> {
            synchronized (lifecycleLock) {
                var status = runtimeIndexService.status();
                if (closed || switching || current != selected || !selected.isActive() || session.hasClient() || selected.runtime() != null
                        || status.phase() != RuntimeIndexService.Phase.WAITING
                        && !(status.phase() == RuntimeIndexService.Phase.FAILED && status.sourceKind() == IndexIdentity.Kind.RUNTIME)) return;
                // Live admission retired an offline load. Resume browsing only if no newer
                // client or inventory load has taken ownership in the meantime.
                runtimeIndexService.restore(selected.profile().dataDirectory(), selected.profile().workspaceDirectory());
            }
            onUi(CompanionUi::runtimeChanged);
        }); } catch (RejectedExecutionException failure) {
            if (!closed) throw failure;
        }
    }

    private void finishRuntimeInstallation(RuntimeBinding installed, ProjectScope selected) {
        CompanionUi view;
        synchronized (lifecycleLock) {
            if (!selected.isActive() || selected.runtime() != installed || current != selected) return;
            view = ui;
        }
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
        }
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
        CodeModeJobService jobs = mcpJobs;
        if (jobs == null) jobs = new CodeModeJobService(executionRuns, this::requireProject,
                this::isConnected, this::runtimeContext);
        startMcpServer(jobs, CompanionMcpServer.MCP_PORT);
    }

    CompanionMcpServer startMcpServer(CodeModeJobService jobs, int port) throws Exception {
        if (mcpServer != null) throw new IllegalStateException("MCP server is already running");
        if (mcpJobs != null && mcpJobs != jobs) throw new IllegalStateException("MCP jobs belong to the application");
        mcpJobs = jobs;
        CompanionMcpServer server = new CompanionMcpServer(this, jobs, port);
        try {
            server.start();
            mcpServer = server;
            updateMcpStatus(new ServiceStatus(ServiceStatus.State.AVAILABLE, "Listening",
                    server.endpointUrl()));
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
            notifications.publish(Severity.ERROR, "MCP startup failed", exception.toString(), Source.application("MCP"));
            exception.printStackTrace(System.err);
        }
    }

    public CompletableFuture<Void> setMcpEnabled(boolean enabled) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Application is closed"));
        return CompletableFuture.runAsync(() -> {
            if (closed) return;
            if (enabled) {
                if (mcpServer == null) startOptionalMcpServer();
            } else {
                updateMcpStatus(new ServiceStatus(ServiceStatus.State.PENDING, "Stopping", "Stopping the MCP server"));
                try { closeMcpServer(); }
                catch (RuntimeException failure) {
                    updateMcpStatus(new ServiceStatus(ServiceStatus.State.FAILED, "Unavailable", failure.toString()));
                    notifications.publish(Severity.ERROR, "Unable to stop MCP", failure.toString(), Source.application("MCP"));
                }
            }
        }, mcpWorker);
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
                scriptExecutions, executionRuns, notifications, editorRuns, runtimeIndexService, this::openDebugFrame, this::exit, this, this::setMcpEnabled, itemIcons);
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
        try { UIUtils.onEdtAndWait(view::refreshProfile); }
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
    private RuntimeIndexService.Status lastIndexStatus;
    private void updateRuntimeIndexUi(RuntimeIndexService.Status status) {
        if (!closed && !switching && status.phase() == RuntimeIndexService.Phase.FAILED && !status.equals(lastIndexStatus)) {
            notifications.publish(Severity.ERROR, "Class indexing failed", status.detail() + (status.failure() == null ? "" : "\n" + status.failure()),
                    Source.capture(current, "Index", null));
        }
        lastIndexStatus = status;
        synchronized (lifecycleLock) {
            var installed = current == null ? null : current.runtime();
            boolean staleLocalIndex = false;
            if (status.phase() == RuntimeIndexService.Phase.FAILED && status.sourceKind() == IndexIdentity.Kind.LOCAL
                    && installed != null && installed.snapshot().localGuard() != null) {
                try { installed.snapshot().localGuard().checkAll(); }
                catch (IOException failure) { staleLocalIndex = true; }
            }
            if (status.phase() == RuntimeIndexService.Phase.EMPTY || staleLocalIndex) {
                closeRuntime();
                onUi(CompanionUi::runtimeChanged);
            }
        }
        onUi(view -> view.setRuntimeIndexStatus(status));
    }
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

    @Override public IndexIdentity.Kind indexSourceKind() {
        var installed = currentRuntime();
        return installed == null ? null : installed.snapshot().identity().kind();
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
