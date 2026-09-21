package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.storage.InstancePaths;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.ProjectSelectionRequest;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ProtocolBindings;
import com.github.minecraft_ta.totaldebug.storage.CompanionSessionDescriptor;
import com.github.minecraft_ta.totaldebug.storage.AppPaths;
import com.github.minecraft_ta.totaldebug.storage.LaunchCache;
import com.github.minecraft_ta.totaldebug.storage.DiagnosticLogs;
import com.github.minecraft_ta.totaldebug.storage.CompanionLaunchContract;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.decompile.SourceTarget;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReadyMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.DebugTargetMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.OpenClassMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.FocusWindowMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerManifestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerSourceRequestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RetryRuntimeInventoryMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RuntimeInventoryMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.StopScriptMessage;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.tth05.scnet.Client;
import com.github.tth05.scnet.IConnectionListener;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import com.github.tth05.scnet.message.AbstractMessage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CompanionAppClient implements AutoCloseable {

    private final Path workspaceDirectory;
    private final Path dataDirectory;
    private final Path appDirectory;
    private final Path appHome;
    private final Path instanceDescriptorFile;
    private final Path instanceKeyFile;
    private final String profileId;
    private final CompanionAppInstaller installer;
    private final RuntimeInventoryPublisher runtimeInventoryPublisher;
    private final Object runtimeInventoryLock = new Object();
    private final ExecutorService runtimeInventoryWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TotalDebug runtime inventory");
        thread.setDaemon(true);
        return thread;
    });
    private final CompanionTimeouts timeouts;
    private final CompanionForegroundHandoff foregroundHandoff;
    private static final class Connection {
        final Client client = new Client();
        final CompanionSessionDescriptor descriptor;
        final CompletableFuture<Void> authenticated = new CompletableFuture<>();
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final AtomicBoolean finished = new AtomicBoolean();
        volatile String token;
        volatile boolean rejected;

        Connection(CompanionSessionDescriptor descriptor, String token) { this.descriptor = descriptor; this.token = token; }
        boolean authenticated() { return authenticated.isDone() && !authenticated.isCompletedExceptionally(); }
    }
    private final Object connectionLock = new Object();
    private volatile Connection connection;
    private volatile CompanionDiscovery discovery;

    private volatile Consumer<RunScriptMessage> scriptRequestHandler = message -> TotalDebug.LOGGER.warn(
            "Ignoring companion script request {} because no handler is installed",
            message.scriptId()
    );
    private volatile IntConsumer stopScriptHandler = scriptId -> TotalDebug.LOGGER.warn(
            "Ignoring companion stop-script request {} because no handler is installed",
            scriptId
    );
    private volatile Runnable sessionClosedHandler = () -> { };
    private volatile Consumer<CompanionStartupProgress> progressListener = progress -> { };
    private volatile boolean closing;
    private volatile RuntimeInventoryMessage runtimeInventoryState = RuntimeInventoryMessage.preparing(
            "Waiting for the Minecraft session"
    );

    private Process launchedProcess;
    private Path processLog;
    private Future<?> runtimeInventoryTask;

    public CompanionAppClient(Path totalDebugDirectory) {
        this(totalDebugDirectory, "");
    }

    public CompanionAppClient(Path totalDebugDirectory, String developmentJar) {
        this(
                totalDebugDirectory,
                developmentJar,
                CompanionTimeouts.DEFAULT,
                new CompanionForegroundHandoff(WindowsForegroundPermission.currentPlatform())
        );
    }

    CompanionAppClient(Path totalDebugDirectory, CompanionTimeouts timeouts) {
        this(
                totalDebugDirectory,
                "",
                timeouts,
                new CompanionForegroundHandoff(WindowsForegroundPermission.currentPlatform())
        );
    }

    CompanionAppClient(
            Path totalDebugDirectory,
            CompanionTimeouts timeouts,
            CompanionForegroundHandoff foregroundHandoff
    ) {
        this(totalDebugDirectory, "", timeouts, foregroundHandoff);
    }

    CompanionAppClient(
            Path totalDebugDirectory,
            String developmentJar,
            CompanionTimeouts timeouts,
            CompanionForegroundHandoff foregroundHandoff
    ) {
        Path root = Objects.requireNonNull(totalDebugDirectory, "totalDebugDirectory").toAbsolutePath().normalize();
        Path workspace = root.getParent();
        if (workspace == null) {
            throw new IllegalArgumentException("TotalDebug directory must have a workspace parent: " + root);
        }

        this.workspaceDirectory = workspace;
        this.dataDirectory = InstancePaths.forGame(workspace).home();
        this.appDirectory = InstancePaths.installationDirectory(this.workspaceDirectory);
        var appPaths = AppPaths.defaults(System.getenv());
        this.appHome = appPaths.home();
        this.instanceDescriptorFile = appPaths.instanceDescriptor();
        this.instanceKeyFile = appPaths.instanceKey();
        this.profileId = InstancePaths.profileId(this.workspaceDirectory);
        this.installer = new CompanionAppInstaller(this.appDirectory, developmentJar);
        this.runtimeInventoryPublisher = new RuntimeInventoryPublisher(this.dataDirectory);
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.foregroundHandoff = Objects.requireNonNull(foregroundHandoff, "foregroundHandoff");

        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "TotalDebug companion shutdown"));
    }

    public void startDiscovery(BooleanSupplier enabled) {
        synchronized (connectionLock) {
            if (closing || discovery != null) return;
            discovery = new CompanionDiscovery(instanceDescriptorFile.getParent(), this::tryAutomaticConnection, this::isConnected, enabled);
            discovery.start();
        }
    }

    public boolean isConnected() {
        var current = connection;
        return current != null && !current.finished.get() && current.client.isConnected() && current.ready.isDone() && !current.ready.isCompletedExceptionally();
    }

    private synchronized CompanionDiscovery.Result tryAutomaticConnection() {
        if (closing) return CompanionDiscovery.Result.IDLE;
        if (isConnected()) return CompanionDiscovery.Result.CONNECTED;
        CompanionSessionDescriptor descriptor;
        String token;
        try {
            descriptor = readLiveDescriptor();
            if (descriptor == null || !profileId.equals(descriptor.selectedProfileId())) return CompanionDiscovery.Result.IDLE;
            token = readInstanceKey();
        } catch (FileSystemException failure) {
            TotalDebug.LOGGER.debug("Companion discovery files are temporarily unavailable: {}", failure.getMessage());
            return CompanionDiscovery.Result.RETRY;
        } catch (IOException failure) {
            TotalDebug.LOGGER.warn("Companion discovery unavailable: {}", failure.getMessage());
            return CompanionDiscovery.Result.REJECTED;
        }
        try {
            connectAndAwait(descriptor, token);
            return CompanionDiscovery.Result.CONNECTED;
        } catch (IOException failure) {
            var attempt = connection;
            TotalDebug.LOGGER.debug("Companion connection unavailable: {}", failure.getMessage());
            return attempt != null && attempt.rejected ? CompanionDiscovery.Result.REJECTED : CompanionDiscovery.Result.RETRY;
        }
    }

    public void setScriptRequestHandler(Consumer<RunScriptMessage> handler) {
        this.scriptRequestHandler = Objects.requireNonNull(handler, "handler");
    }

    public void setStopScriptHandler(IntConsumer handler) {
        this.stopScriptHandler = Objects.requireNonNull(handler, "handler");
    }

    public void setSessionClosedHandler(Runnable handler) {
        this.sessionClosedHandler = Objects.requireNonNull(handler, "handler");
    }

    public void setProgressListener(Consumer<CompanionStartupProgress> listener) {
        this.progressListener = Objects.requireNonNull(listener, "listener");
    }

    private Consumer<ServerSourceRequestMessage> serverSourceRequestHandler = message ->
            send(new ServerManifestMessage(message.sessionId(), message.requestId(), message.source(),
                    "Server source request handler is not installed", 0, 0, new byte[0]));

    public void setServerSourceRequestHandler(Consumer<ServerSourceRequestMessage> handler) {
        this.serverSourceRequestHandler = Objects.requireNonNull(handler);
    }

    private final Object serverManifestLock = new Object();
    private final List<ServerManifestMessage> serverManifest = new ArrayList<>();

    public void acceptServerManifest(ServerManifestMessage message) {
        synchronized (this.serverManifestLock) {
            if (!message.baseline()) {
                send(message);
                return;
            }
            if (message.offset() == 0) this.serverManifest.clear();
            // Replay the bounded current transfer when Companion opens after joining the server.
            if (this.serverManifest.size() >= ServerManifestMessage.MAX_BYTES / ServerManifestMessage.CHUNK_BYTES + 1) {
                this.serverManifest.clear();
                message = ServerManifestMessage.unavailable("Invalid server manifest transfer");
            }
            this.serverManifest.add(message);
            send(message);
        }
    }

    private void sendServerManifest() {
        synchronized (this.serverManifestLock) {
            if (this.serverManifest.isEmpty()) {
                send(ServerManifestMessage.unavailable(
                        "No server handshake is available. Join a server running matching TotalDebug."));
            } else {
                for (var message : this.serverManifest) send(message);
            }
        }
    }

    public void sendExecutionResult(int scriptId, ExecutionResult result) {
        if (!isAuthenticated()) {
            TotalDebug.LOGGER.debug(
                    "Discarding execution result {} for script {} because the session is not authenticated",
                    result.status(),
                    scriptId
            );
            return;
        }
        send(new ExecutionResultMessage(scriptId, result));
    }

    public synchronized void openClassAndFocus(
            String binaryName,
            SourceTarget sourceTarget,
            Runnable beforeTransfer
    ) throws IOException {
        Objects.requireNonNull(binaryName, "binaryName");
        Objects.requireNonNull(beforeTransfer, "beforeTransfer");
        Objects.requireNonNull(sourceTarget, "sourceTarget");
        ensureConnectedAndReady();
        transferForeground(beforeTransfer, () -> enqueueOpenClass(binaryName, sourceTarget));
    }

    public synchronized void focus(Runnable beforeFocus) throws IOException {
        Objects.requireNonNull(beforeFocus, "beforeFocus");
        ensureConnectedAndReady();
        transferForeground(
                beforeFocus,
                () -> send(new FocusWindowMessage())
        );
    }

    private void enqueueOpenClass(String binaryName, SourceTarget sourceTarget) {
        CompanionSourceTargetCodec.WireTarget wireTarget = CompanionSourceTargetCodec.encode(sourceTarget);
        send(new OpenClassMessage(
                binaryName,
                wireTarget.javaElementType(),
                wireTarget.identifier()
        ));
    }

    private void transferForeground(Runnable beforeTransfer, Runnable sendRequest) throws IOException {
        var current = connection;
        CompanionSessionDescriptor descriptor = current == null ? null : current.descriptor;
        if (descriptor == null
                || !ProcessHandle.of(descriptor.processId()).map(ProcessHandle::isAlive).orElse(false)) {
            throw new IOException("Companion is no longer running");
        }
        this.foregroundHandoff.transfer(descriptor.processId(), beforeTransfer, sendRequest);
    }

    private void registerProtocol(Connection attempt) {
        var transport = attempt.client;
        transport.setMessageBus(new DefaultMessageBus() {
            @Override public void post(AbstractMessage message) {
                if (!closing && connection == attempt) super.post(message);
            }
        });
        transport.getMessageProcessor().setMaxFrameSize(DefaultMessageProcessor.DEFAULT_MAX_FRAME_SIZE);
        transport.getMessageProcessor().setMaxStringLength(DefaultMessageProcessor.DEFAULT_MAX_STRING_LENGTH);
        ProtocolBindings.registerMod(transport.getMessageProcessor());
        transport.getMessageBus().listenAlways(ServerHelloMessage.class, message -> handleServerHello(attempt, message));
        transport.getMessageBus().listenAlways(RetryRuntimeInventoryMessage.class, message -> startRuntimeInventoryPreparation(true));
        transport.getMessageBus().listenAlways(ReadyMessage.class, message -> {
            if (!attempt.authenticated()) {
                failSession(attempt, "Companion sent Ready before the session handshake completed", null);
                return;
            }
            attempt.ready.complete(null);
        });
        transport.getMessageBus().listenAlways(ServerSourceRequestMessage.class, message -> {
            if (!attempt.authenticated()) {
                failSession(attempt, "Companion requested server details before authentication", null);
                return;
            }
            this.serverSourceRequestHandler.accept(message);
        });
        transport.getMessageBus().listenAlways(RunScriptMessage.class, message -> {
            if (!attempt.authenticated()) {
                failSession(attempt, "Companion sent a script request before authentication", null);
                return;
            }
            var inventory = this.runtimeInventoryState;
            if (inventory.state() != RuntimeInventoryMessage.AVAILABLE || !inventory.inventoryId().equals(message.inventoryId())) {
                sendExecutionResult(message.scriptId(), ExecutionResult.fromStatus(ExecutionStatus.COMPILATION_FAILED,
                        "The script was compiled against a different runtime inventory. Wait for Companion to load the current index."));
                return;
            }
            this.scriptRequestHandler.accept(message);
        });
        transport.getMessageBus().listenAlways(StopScriptMessage.class, message -> {
            if (!attempt.authenticated()) {
                failSession(attempt, "Companion sent a stop-script request before authentication", null);
                return;
            }
            this.stopScriptHandler.accept(message.scriptId());
        });
        transport.addConnectionListener(new IConnectionListener() {
            @Override public void onConnected() {
                if (closing || connection != attempt) { transport.close(); return; }
                transport.getMessageProcessor().enqueueMessage(new ClientHelloMessage(CompanionProtocol.VERSION,
                        attempt.token, profileId, dataDirectory.toString(), workspaceDirectory.toString()));
            }
            @Override public void onDisconnected() {
                if (connection == attempt) finishConnection(attempt, new IOException("Companion disconnected"));
            }
            @Override public void onConnectionError(Throwable cause) {
                if (connection == attempt) failSession(attempt, "Companion transport failed", cause);
            }
        });
    }

    private void handleServerHello(Connection attempt, ServerHelloMessage message) {
        if (closing || connection != attempt) return;
        if (attempt.authenticated.isDone()) {
            failSession(attempt, "Companion sent more than one session handshake response", null);
            return;
        }
        if (message.protocolVersion() != CompanionProtocol.VERSION || !message.accepted()) {
            attempt.rejected = true;
            failSession(attempt, message.protocolVersion() != CompanionProtocol.VERSION
                    ? "Companion protocol mismatch: expected " + CompanionProtocol.VERSION + ", got " + message.protocolVersion()
                    : "Companion rejected the session handshake: " + message.rejectionReason(), null);
            return;
        }
        attempt.token = null;
        attempt.authenticated.complete(null);
        send(new DebugTargetMessage("minecraft-client", "Minecraft Client", DebugTargetMessage.LOCAL_JVM, ProcessHandle.current().pid()));
        sendServerManifest();
        startRuntimeInventoryPreparation(false);
    }

    private boolean isAuthenticated() {
        var current = connection;
        return current != null && current.authenticated() && !current.finished.get();
    }

    private void send(AbstractMessage message) {
        var current = connection;
        if (current == null || !current.authenticated() || !current.client.isConnected() || closing) return;
        try { current.client.getMessageProcessor().enqueueMessage(message); }
        catch (RejectedExecutionException rejected) { TotalDebug.LOGGER.debug("Companion disconnected before message delivery", rejected); }
    }

    private void ensureConnectedAndReady() throws IOException {
        if (closing) throw new IOException("Companion client is closed");
        try {
            CompanionSessionDescriptor descriptor = discoverOrStartCompanion();
            String token = readInstanceKey();
            boolean retained = ProjectSelectionRequest.send(descriptor.projectPort(), new ClientHelloMessage(
                    CompanionProtocol.VERSION, token, profileId, dataDirectory.toString(), workspaceDirectory.toString()));
            if (!retained) resetConnection();
            reportProgress(CompanionStartupProgress.connecting());
            connectAndAwait(descriptor, token);
            reportProgress(CompanionStartupProgress.ready());
        } catch (IOException failure) {
            reportProgress(CompanionStartupProgress.failed(failure.getMessage()));
            throw failure;
        }
    }

    private void connectAndAwait(CompanionSessionDescriptor descriptor, String token) throws IOException {
        var previous = connection;
        if (isConnected() && sameEndpoint(previous.descriptor, descriptor)) return;
        resetConnection();
        var attempt = new Connection(descriptor, token);
        registerProtocol(attempt);
        try {
            synchronized (connectionLock) {
                if (closing) throw new IOException("Companion client is closed");
                connection = attempt;
                if (!attempt.client.connect(sessionAddress(descriptor.port()))) throw new IOException("Unable to connect to Companion");
            }
            if (closing || connection != attempt) throw new IOException("Companion connection attempt was cancelled");
            await(attempt.authenticated, timeouts.handshake(), "Companion session handshake");
            await(attempt.ready, timeouts.readiness(), "Companion readiness");
            if (closing || connection != attempt || attempt.finished.get() || !attempt.client.isConnected())
                throw new IOException("Companion connection attempt was cancelled");
        } catch (IOException failure) {
            finishConnection(attempt, failure);
            attempt.client.close();
            throw failure;
        }
    }

    private static boolean sameEndpoint(CompanionSessionDescriptor first, CompanionSessionDescriptor second) {
        return first.protocolVersion() == second.protocolVersion() && first.processId() == second.processId()
                && first.port() == second.port() && first.projectPort() == second.projectPort();
    }
    private void startRuntimeInventoryPreparation(boolean force) {
        synchronized (this.runtimeInventoryLock) {
            if (this.closing || !isAuthenticated()) {
                return;
            }
            if (!force && this.runtimeInventoryState.state() == RuntimeInventoryMessage.AVAILABLE) {
                sendRuntimeInventoryState();
                return;
            }
            if (this.runtimeInventoryTask != null && !this.runtimeInventoryTask.isDone()) {
                sendRuntimeInventoryState();
                return;
            }

            this.runtimeInventoryState = RuntimeInventoryMessage.preparing("Discovering runtime class sources");
            sendRuntimeInventoryState();
            this.runtimeInventoryTask = this.runtimeInventoryWorker.submit(() -> {
                try {
                    RuntimeInventoryPublisher.PublishedInventory published = this.runtimeInventoryPublisher.publish();
                    this.runtimeInventoryState = RuntimeInventoryMessage.available(
                            published.id(),
                            published.file().toString()
                    );
                    sendRuntimeInventoryState();
                } catch (IOException | RuntimeException exception) {
                    TotalDebug.LOGGER.error("Unable to publish the Companion runtime inventory", exception);
                    String detail = exception.getMessage();
                    this.runtimeInventoryState = RuntimeInventoryMessage.failed(
                            detail == null || detail.isBlank() ? "Runtime inventory preparation failed" : detail
                    );
                    sendRuntimeInventoryState();
                }
            });
        }
    }

    private void sendRuntimeInventoryState() {
        send(this.runtimeInventoryState);
    }

    static InetSocketAddress sessionAddress(int port) {
        return new InetSocketAddress(CompanionLaunchContract.IPV4_LOOPBACK_HOST, port);
    }

    private CompanionSessionDescriptor discoverOrStartCompanion() throws IOException {
        Files.createDirectories(this.appHome);
        CompanionSessionDescriptor existing = readLiveDescriptor();
        if (existing != null) {
            TotalDebug.LOGGER.info("Connecting to TotalDebugCompanion process {}", existing.processId());
            return existing;
        }

        CompanionInstallation installation;
        try {
            installation = this.installer.resolveOrInstall(this::reportProgress);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while installing the companion app", exception);
        }
        AppPaths paths = new AppPaths(this.appHome);
        var launch = LaunchCache.stage(paths, installation.companionJar());
        DiagnosticLogs.Reservation log;
        try {
            log = DiagnosticLogs.reserve(paths);
        } catch (IOException | RuntimeException exception) {
            launch.close();
            throw exception;
        }
        this.processLog = log.log();
        Path javaExecutable = CompanionJavaRuntime.resolveCurrentExecutable();
        try {
            var command = new ArrayList<>(buildLaunchCommand(javaExecutable, launch.path(), this.appHome));
            command.add(1, "-D" + DiagnosticLogs.LOG_PROPERTY + "=" + this.processLog);
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(this.processLog.toFile());
            reportProgress(CompanionStartupProgress.starting());
            synchronized (connectionLock) {
                if (closing) throw new IOException("Companion client is closed");
                this.launchedProcess = processBuilder.start();
            }
        } catch (IOException | RuntimeException exception) {
            log.close();
            launch.close();
            throw exception;
        }
        this.launchedProcess.onExit().thenRun(() -> {
            try (launch; log) {
                // Keep both pins until the child exits, including a slow or failed startup.
            } catch (IOException exception) {
                TotalDebug.LOGGER.warn("Unable to release Companion launch files", exception);
            }
        });
        TotalDebug.LOGGER.info("Started TotalDebugCompanion from {}; output is written to {}",
                launch.path(), this.processLog);

        return awaitDescriptor(this.instanceDescriptorFile);
    }

    private CompanionSessionDescriptor readLiveDescriptor() throws IOException {
        if (!Files.isRegularFile(this.instanceDescriptorFile)) {
            return null;
        }
        if (!isInstanceLockHeld()) {
            return null;
        }
        CompanionSessionDescriptor descriptor = CompanionSessionDescriptor.read(this.instanceDescriptorFile, CompanionProtocol.VERSION);
        if (!ProcessHandle.of(descriptor.processId()).map(ProcessHandle::isAlive).orElse(false)) {
            throw new IOException("Companion descriptor names a stopped process while its instance lock is held");
        }
        if (!Files.isRegularFile(this.instanceKeyFile)) {
            throw new IOException("Companion instance key is missing");
        }
        return descriptor;
    }

    private boolean isInstanceLockHeld() throws IOException {
        Path lockFile = new AppPaths(this.appHome).instanceLock();
        if (!Files.isRegularFile(lockFile)) return false;
        try (FileChannel channel = FileChannel.open(
                lockFile,
                StandardOpenOption.WRITE
        )) {
            FileLock lock = null;
            try {
                lock = channel.tryLock();
                return lock == null;
            } catch (OverlappingFileLockException exception) {
                return true;
            } finally {
                if (lock != null) {
                    lock.release();
                }
            }
        }
    }

    private String readInstanceKey() throws IOException {
        String token = Files.readString(this.instanceKeyFile, StandardCharsets.US_ASCII).trim();
        if (token.length() < 32) {
            throw new IOException("Companion instance key is invalid");
        }
        return token;
    }

    static List<String> buildLaunchCommand(Path javaExecutable, Path launchJar, Path appHome) {
        return List.of(
                javaExecutable.toString(),
                "-jar",
                launchJar.toString(),
                CompanionLaunchContract.APP_HOME_ARGUMENT,
                appHome.toString()
        );
    }

    private void reportProgress(CompanionStartupProgress progress) {
        this.progressListener.accept(progress);
    }

    private CompanionSessionDescriptor awaitDescriptor(Path descriptorFile) throws IOException {
        long startedAt = System.nanoTime();
        long timeoutNanos = this.timeouts.processStart().toNanos();
        while (System.nanoTime() - startedAt < timeoutNanos) {
            if (Files.isRegularFile(descriptorFile)) {
                CompanionSessionDescriptor descriptor = CompanionSessionDescriptor.read(descriptorFile, CompanionProtocol.VERSION);
                if (!ProcessHandle.of(descriptor.processId()).map(ProcessHandle::isAlive).orElse(false)) {
                    throw new IOException("Companion descriptor names a stopped process");
                }
                return descriptor;
            }
            Process started = this.launchedProcess;
            if (started != null && !started.isAlive()) {
                throw new IOException("Companion exited before publishing its endpoint; see " + this.processLog);
            }
            try {
                Thread.sleep(this.timeouts.descriptorPollInterval());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the Companion session descriptor", exception);
            }
        }
        throw new IOException(
                "Companion did not publish its session descriptor within " + this.timeouts.processStart()
                        + "; see " + this.processLog
        );
    }

    private static void await(CompletableFuture<Void> future, Duration timeout, String operation) throws IOException {
        try {
            future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + operation, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(operation + " failed", cause);
        } catch (TimeoutException exception) {
            throw new IOException(operation + " did not complete within " + timeout, exception);
        }
    }

    private void failSession(Connection attempt, String message, Throwable cause) {
        IOException exception = cause == null ? new IOException(message) : new IOException(message, cause);
        finishConnection(attempt, exception);
        attempt.client.close();
    }

    private void finishConnection(Connection attempt, IOException failure) {
        synchronized (attempt) {
            attempt.authenticated.completeExceptionally(failure);
            attempt.ready.completeExceptionally(failure);
            if (attempt.finished.compareAndSet(false, true)) notifySessionClosed();
        }
    }

    private void notifySessionClosed() {
        try {
            this.sessionClosedHandler.run();
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.warn("The Companion session-close handler failed", exception);
        }
    }

    private void resetConnection() {
        Connection previous;
        synchronized (connectionLock) { previous = connection; connection = null; }
        if (previous != null) {
            finishConnection(previous, new IOException("Companion connection was replaced"));
            previous.client.close();
        }
    }

    @Override
    public void close() {
        synchronized (connectionLock) {
            if (closing) return;
            closing = true;
        }
        var watching = discovery;
        if (watching != null) watching.close();
        resetConnection();
        synchronized (this.runtimeInventoryLock) {
            this.runtimeInventoryWorker.shutdownNow();
        }
    }

}
