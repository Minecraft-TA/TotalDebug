package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.decompile.SourceTarget;
import com.github.minecraft_ta.totaldebug.client.companion.message.ClientHelloMessage;
import com.github.minecraft_ta.totaldebug.client.companion.message.CompanionReadyMessage;
import com.github.minecraft_ta.totaldebug.client.companion.message.DecompileOrOpenMessage;
import com.github.minecraft_ta.totaldebug.client.companion.message.FocusWindowMessage;
import com.github.minecraft_ta.totaldebug.client.companion.message.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.client.companion.message.ScriptStatusMessage;
import com.github.minecraft_ta.totaldebug.client.companion.message.ServerHelloMessage;
import com.github.minecraft_ta.totaldebug.client.companion.message.StopScriptMessage;
import com.github.minecraft_ta.totaldebug.script.ScriptStatusType;
import com.github.tth05.scnet.Client;
import com.github.tth05.scnet.IConnectionListener;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class CompanionAppClient implements AutoCloseable {
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

    private final Path workspaceDirectory;
    private final Path dataDirectory;
    private final Path appDirectory;
    private final Path sessionsDirectory;
    private final CompanionAppInstaller installer;
    private final RuntimeClassIndex runtimeClassIndex;
    private final CompanionTimeouts timeouts;
    private final CompanionForegroundHandoff foregroundHandoff;
    private final Client client = new Client();
    private volatile CompletableFuture<Void> authenticated = new CompletableFuture<>();
    private volatile CompletableFuture<Void> ready = new CompletableFuture<>();

    private volatile BiConsumer<String, SourceTarget> decompileRequestHandler = (binaryName, sourceTarget) -> TotalDebug.LOGGER.warn(
            "Ignoring companion decompile request for {} because no handler is installed",
            binaryName
    );
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
    private volatile String sessionToken;
    private volatile long negotiatedCapabilities;
    private volatile boolean closing;
    private volatile boolean transportExpected;

    private Process process;
    private Path processLog;
    private Path sessionDirectory;
    private Path sessionDescriptorFile;

    public CompanionAppClient(Path totalDebugDirectory) {
        this(
                totalDebugDirectory,
                CompanionTimeouts.DEFAULT,
                new CompanionForegroundHandoff(WindowsForegroundPermission.currentPlatform())
        );
    }

    CompanionAppClient(Path totalDebugDirectory, CompanionTimeouts timeouts) {
        this(
                totalDebugDirectory,
                timeouts,
                new CompanionForegroundHandoff(WindowsForegroundPermission.currentPlatform())
        );
    }

    CompanionAppClient(
            Path totalDebugDirectory,
            CompanionTimeouts timeouts,
            CompanionForegroundHandoff foregroundHandoff
    ) {
        Path root = Objects.requireNonNull(totalDebugDirectory, "totalDebugDirectory").toAbsolutePath().normalize();
        Path workspace = root.getParent();
        if (workspace == null) {
            throw new IllegalArgumentException("TotalDebug directory must have a workspace parent: " + root);
        }

        this.workspaceDirectory = workspace;
        this.dataDirectory = root.resolve("data");
        this.appDirectory = root.resolve("companion-app");
        this.sessionsDirectory = root.resolve("sessions");
        this.installer = new CompanionAppInstaller(this.appDirectory);
        this.runtimeClassIndex = new RuntimeClassIndex(this.dataDirectory);
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.foregroundHandoff = Objects.requireNonNull(foregroundHandoff, "foregroundHandoff");

        configureTransport();
        registerProtocol();
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "TotalDebug companion shutdown"));
    }

    public void setDecompileRequestHandler(BiConsumer<String, SourceTarget> handler) {
        this.decompileRequestHandler = Objects.requireNonNull(handler, "handler");
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

    public void sendScriptStatus(int scriptId, ScriptStatusType type, String message) {
        if (!hasCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION)) {
            TotalDebug.LOGGER.debug(
                    "Discarding script status {} for script {} because script execution is not negotiated",
                    type,
                    scriptId
            );
            return;
        }
        this.client.getMessageProcessor().enqueueMessage(new ScriptStatusMessage(scriptId, type, message));
    }

    public synchronized void openAndFocus(
            Path sourceFile,
            SourceTarget sourceTarget,
            Runnable beforeTransfer
    ) throws IOException {
        Objects.requireNonNull(beforeTransfer, "beforeTransfer");
        Objects.requireNonNull(sourceTarget, "sourceTarget");
        ensureConnectedAndReady();
        transferForeground(beforeTransfer, () -> enqueueOpen(sourceFile, sourceTarget));
    }

    public synchronized void openInPlace(Path sourceFile, SourceTarget sourceTarget) throws IOException {
        Objects.requireNonNull(sourceTarget, "sourceTarget");
        ensureConnectedAndReady();
        enqueueOpen(sourceFile, sourceTarget);
    }

    public synchronized void focus(Runnable beforeFocus) throws IOException {
        Objects.requireNonNull(beforeFocus, "beforeFocus");
        ensureConnectedAndReady();
        transferForeground(
                beforeFocus,
                () -> this.client.getMessageProcessor().enqueueMessage(new FocusWindowMessage())
        );
    }

    public Path dataDirectory() {
        return this.dataDirectory;
    }

    private void enqueueOpen(Path sourceFile, SourceTarget sourceTarget) {
        CompanionSourceTargetCodec.WireTarget wireTarget = CompanionSourceTargetCodec.encode(sourceTarget);
        this.client.getMessageProcessor().enqueueMessage(new DecompileOrOpenMessage(
                sourceFile.toAbsolutePath().normalize().toString(),
                wireTarget.javaElementType(),
                wireTarget.identifier()
        ));
    }

    private void transferForeground(Runnable beforeTransfer, Runnable sendRequest) throws IOException {
        Process companionProcess = this.process;
        if (companionProcess == null || !companionProcess.isAlive()) {
            throw new IOException("The authenticated Companion child is no longer running");
        }
        this.foregroundHandoff.transfer(companionProcess.pid(), beforeTransfer, sendRequest);
    }

    private void configureTransport() {
        this.client.getMessageProcessor().setMaxFrameSize(DefaultMessageProcessor.RECOMMENDED_MAX_FRAME_SIZE);
        this.client.getMessageProcessor().setMaxStringLength(DefaultMessageProcessor.RECOMMENDED_MAX_STRING_LENGTH);
    }

    private void registerProtocol() {
        this.client.getMessageProcessor().registerMessage(
                CompanionProtocol.READY,
                CompanionReadyMessage.class,
                CompanionReadyMessage::new
        );
        this.client.getMessageProcessor().registerMessage(
                CompanionProtocol.DECOMPILE_OR_OPEN,
                DecompileOrOpenMessage.class,
                DecompileOrOpenMessage::new
        );
        this.client.getMessageProcessor().registerMessage(
                CompanionProtocol.RUN_SCRIPT,
                RunScriptMessage.class,
                RunScriptMessage::new
        );
        this.client.getMessageProcessor().registerMessage(
                CompanionProtocol.SCRIPT_STATUS,
                ScriptStatusMessage.class
        );
        this.client.getMessageProcessor().registerMessage(
                CompanionProtocol.STOP_SCRIPT,
                StopScriptMessage.class,
                StopScriptMessage::new
        );
        this.client.getMessageProcessor().registerMessage(CompanionProtocol.FOCUS_WINDOW, FocusWindowMessage.class);
        this.client.getMessageProcessor().registerMessage(CompanionProtocol.CLIENT_HELLO, ClientHelloMessage.class);
        this.client.getMessageProcessor().registerMessage(
                CompanionProtocol.SERVER_HELLO,
                ServerHelloMessage.class,
                ServerHelloMessage::new
        );

        this.client.getMessageBus().listenAlways(ServerHelloMessage.class, this::handleServerHello);
        this.client.getMessageBus().listenAlways(CompanionReadyMessage.class, message -> {
            CompletableFuture<Void> authentication = this.authenticated;
            if (!authentication.isDone() || authentication.isCompletedExceptionally()) {
                failSession("Companion sent Ready before the session handshake completed", null);
                return;
            }
            this.ready.complete(null);
        });
        this.client.getMessageBus().listenAlways(DecompileOrOpenMessage.class, message -> {
            if (!hasCapability(CompanionProtocol.CAPABILITY_REVERSE_DECOMPILE)) {
                failSession("Companion sent a reverse-decompile request without negotiating that capability", null);
                return;
            }
            SourceTarget sourceTarget;
            try {
                sourceTarget = CompanionSourceTargetCodec.decode(message.targetType(), message.targetIdentifier());
            } catch (IllegalArgumentException exception) {
                failSession("Companion sent an invalid source target: " + exception.getMessage(), exception);
                return;
            }
            this.decompileRequestHandler.accept(message.name(), sourceTarget);
        });
        this.client.getMessageBus().listenAlways(RunScriptMessage.class, message -> {
            if (!hasCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION)) {
                failSession("Companion sent a script request without negotiating that capability", null);
                return;
            }
            this.scriptRequestHandler.accept(message);
        });
        this.client.getMessageBus().listenAlways(StopScriptMessage.class, message -> {
            if (!hasCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION)) {
                failSession("Companion sent a stop-script request without negotiating that capability", null);
                return;
            }
            this.stopScriptHandler.accept(message.scriptId());
        });
        this.client.addConnectionListener(new IConnectionListener() {
            @Override
            public void onConnected() {
                CompanionAppClient.this.transportExpected = true;
                String token = CompanionAppClient.this.sessionToken;
                if (token == null) {
                    failSession("Companion transport connected without an active session token", null);
                    return;
                }
                CompanionAppClient.this.client.getMessageProcessor().enqueueMessage(new ClientHelloMessage(
                        CompanionProtocol.VERSION,
                        token,
                        CompanionProtocol.REQUESTED_CAPABILITIES
                ));
            }

            @Override
            public void onDisconnected() {
                notifySessionClosed();
                CompletableFuture<Void> readiness = CompanionAppClient.this.ready;
                if (!CompanionAppClient.this.closing
                        && CompanionAppClient.this.transportExpected
                        && !readiness.isDone()) {
                    failSession("Companion disconnected before the session became ready", null);
                }
            }

            @Override
            public void onConnectionError(Throwable cause) {
                if (!CompanionAppClient.this.closing && CompanionAppClient.this.transportExpected) {
                    failSession("Companion transport failed", cause);
                }
            }
        });
    }

    private void handleServerHello(ServerHelloMessage message) {
        CompletableFuture<Void> authentication = this.authenticated;
        if (authentication.isDone()) {
            failSession("Companion sent more than one session handshake response", null);
            return;
        }
        if (message.protocolVersion() != CompanionProtocol.VERSION) {
            failSession(
                    "Companion protocol mismatch: expected " + CompanionProtocol.VERSION
                            + ", got " + message.protocolVersion(),
                    null
            );
            return;
        }
        if (!message.accepted()) {
            failSession("Companion rejected the session handshake: " + message.rejectionReason(), null);
            return;
        }
        if ((message.capabilities() & CompanionProtocol.CORE_CAPABILITIES) != CompanionProtocol.CORE_CAPABILITIES) {
            failSession(
                    "Companion is missing required core capabilities: negotiated 0x"
                            + Long.toHexString(message.capabilities()),
                    null
            );
            return;
        }

        this.negotiatedCapabilities = message.capabilities();
        this.sessionToken = null;
        authentication.complete(null);
    }

    private boolean hasCapability(long capability) {
        CompletableFuture<Void> authentication = this.authenticated;
        return authentication.isDone()
                && !authentication.isCompletedExceptionally()
                && (this.negotiatedCapabilities & capability) == capability;
    }

    private void ensureConnectedAndReady() throws IOException {
        try {
            boolean indexBuilt = this.runtimeClassIndex.ensurePresent(
                    () -> reportProgress(CompanionStartupProgress.indexing())
            );
            CompletableFuture<Void> readiness = this.ready;
            if (this.client.isConnected() && readiness.isDone() && !readiness.isCompletedExceptionally()) {
                if (indexBuilt) {
                    reportProgress(CompanionStartupProgress.ready());
                }
                return;
            }
            if (this.process != null) {
                if (this.process.isAlive()) {
                    throw new IOException(
                            "The active Companion child lost its authenticated transport; see " + this.processLog
                    );
                }
                resetExitedSession();
            }

            CompanionSessionDescriptor descriptor = startInstalledCompanion();
            InetSocketAddress address = sessionAddress(descriptor.port());
            reportProgress(CompanionStartupProgress.connecting());
            if (!this.client.connect(address)) {
                throw new IOException("Unable to connect to the exact Companion child at " + address);
            }
            CompletableFuture<Void> authentication = this.authenticated;
            readiness = this.ready;
            await(authentication, this.timeouts.handshake(), "Companion session handshake");
            await(readiness, this.timeouts.readiness(), "Companion UI readiness");
            reportProgress(CompanionStartupProgress.ready());
        } catch (IOException exception) {
            reportProgress(CompanionStartupProgress.failed(exception.getMessage()));
            this.client.close();
            if (this.process != null && this.process.isAlive()) {
                this.process.destroy();
            }
            throw exception;
        }
    }

    static InetSocketAddress sessionAddress(int port) {
        return new InetSocketAddress(CompanionLaunchContract.IPV4_LOOPBACK_HOST, port);
    }

    private CompanionSessionDescriptor startInstalledCompanion() throws IOException {
        CompanionInstallation installation;
        try {
            installation = this.installer.resolveOrInstall(this::reportProgress);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while installing the companion app", exception);
        }

        Files.createDirectories(this.dataDirectory);
        Files.createDirectories(this.sessionsDirectory);
        this.sessionDirectory = this.sessionsDirectory.resolve(UUID.randomUUID().toString());
        Files.createDirectory(this.sessionDirectory);
        String sessionId = this.sessionDirectory.getFileName().toString();
        this.sessionDescriptorFile = this.sessionDirectory.resolve(CompanionLaunchContract.SESSION_DESCRIPTOR_FILE_NAME);
        Files.deleteIfExists(this.sessionDescriptorFile);
        Files.copy(
                this.runtimeClassIndex.runtimeSourceManifest(),
                this.sessionDirectory.resolve(CompanionLaunchContract.RUNTIME_SOURCE_MANIFEST_FILE_NAME)
        );
        this.sessionToken = newSessionToken();

        Path logsDirectory = this.appDirectory.resolve("logs");
        Files.createDirectories(logsDirectory);
        this.processLog = logsDirectory.resolve(
                "companion-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss"))
                        + "-" + sessionId + ".log"
        );
        Path javaExecutable = CompanionJavaRuntime.resolveCurrentExecutable();

        ProcessBuilder processBuilder = new ProcessBuilder(
                javaExecutable.toString(),
                "-jar",
                installation.companionJar().toString(),
                CompanionLaunchContract.DATA_DIRECTORY_ARGUMENT,
                this.dataDirectory.toString(),
                CompanionLaunchContract.INDEX_FILE_ARGUMENT,
                this.runtimeClassIndex.indexFile().toString(),
                CompanionLaunchContract.WORKSPACE_DIRECTORY_ARGUMENT,
                this.workspaceDirectory.toString(),
                CompanionLaunchContract.SESSION_DESCRIPTOR_ARGUMENT,
                this.sessionDescriptorFile.toString()
        );
        processBuilder.environment().put(CompanionLaunchContract.TOKEN_ENVIRONMENT_VARIABLE, this.sessionToken);
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(this.processLog.toFile());
        reportProgress(CompanionStartupProgress.starting());
        this.process = processBuilder.start();
        TotalDebug.LOGGER.info(
                "Started per-process TotalDebugCompanion session {} from {} with Minecraft Java {}; output is written to {}",
                this.sessionDirectory.getFileName(),
                installation.companionJar(),
                javaExecutable,
                this.processLog
        );

        return awaitDescriptor(this.sessionDescriptorFile);
    }

    private void reportProgress(CompanionStartupProgress progress) {
        this.progressListener.accept(progress);
    }

    private CompanionSessionDescriptor awaitDescriptor(Path descriptorFile) throws IOException {
        long startedAt = System.nanoTime();
        long timeoutNanos = this.timeouts.processStart().toNanos();
        while (System.nanoTime() - startedAt < timeoutNanos) {
            if (Files.isRegularFile(descriptorFile)) {
                CompanionSessionDescriptor descriptor = CompanionSessionDescriptor.read(descriptorFile);
                if (descriptor.protocolVersion() != CompanionProtocol.VERSION) {
                    throw new IOException(
                            "Companion descriptor protocol mismatch: expected " + CompanionProtocol.VERSION
                                    + ", got " + descriptor.protocolVersion()
                    );
                }
                if (descriptor.processId() != this.process.pid()) {
                    throw new IOException(
                            "Companion descriptor PID mismatch: expected " + this.process.pid()
                                    + ", got " + descriptor.processId()
                    );
                }
                return descriptor;
            }
            if (!this.process.isAlive()) {
                throw new IOException("Companion process exited before publishing its session descriptor; see " + this.processLog);
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

    private void failSession(String message, Throwable cause) {
        IOException exception = cause == null ? new IOException(message) : new IOException(message, cause);
        this.authenticated.completeExceptionally(exception);
        this.ready.completeExceptionally(exception);
        this.client.close();
    }

    private void notifySessionClosed() {
        try {
            this.sessionClosedHandler.run();
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.warn("The Companion session-close handler failed", exception);
        }
    }

    private void resetExitedSession() throws IOException {
        this.transportExpected = false;
        this.client.close();
        cleanupSessionDirectory();
        this.authenticated = new CompletableFuture<>();
        this.ready = new CompletableFuture<>();
        this.negotiatedCapabilities = 0;
        this.sessionToken = null;
        this.process = null;
        this.sessionDirectory = null;
        this.sessionDescriptorFile = null;
    }

    private void cleanupSessionDirectory() throws IOException {
        if (this.sessionDirectory == null) {
            return;
        }
        Path normalizedSession = this.sessionDirectory.toAbsolutePath().normalize();
        if (!this.sessionsDirectory.equals(normalizedSession.getParent())) {
            throw new IOException("Refusing to clean an unexpected Companion session directory: " + normalizedSession);
        }
        if (this.sessionDescriptorFile != null) {
            Files.deleteIfExists(this.sessionDescriptorFile);
        }
        Files.deleteIfExists(normalizedSession.resolve(CompanionLaunchContract.RUNTIME_SOURCE_MANIFEST_FILE_NAME));
        Files.deleteIfExists(normalizedSession);
    }

    private static String newSessionToken() {
        byte[] token = new byte[32];
        TOKEN_RANDOM.nextBytes(token);
        return HexFormat.of().formatHex(token);
    }

    @Override
    public synchronized void close() {
        this.closing = true;
        notifySessionClosed();
        this.client.close();
        if (this.process != null && this.process.isAlive()) {
            this.process.destroy();
        }
        try {
            cleanupSessionDirectory();
        } catch (IOException exception) {
            TotalDebug.LOGGER.warn("Unable to clean Companion session directory {}", this.sessionDirectory, exception);
        }
    }
}
