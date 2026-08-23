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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class CompanionAppClient implements AutoCloseable {
    private final Path workspaceDirectory;
    private final Path dataDirectory;
    private final Path appDirectory;
    private final Path appHome;
    private final Path instanceDescriptorFile;
    private final Path instanceKeyFile;
    private final String profileId;
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

    private Process launchedProcess;
    private Path processLog;
    private CompanionSessionDescriptor activeDescriptor;

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
        this.appHome = CompanionAppHome.resolve();
        this.instanceDescriptorFile = this.appHome.resolve(CompanionLaunchContract.INSTANCE_DESCRIPTOR_FILE_NAME);
        this.instanceKeyFile = this.appHome.resolve(CompanionLaunchContract.INSTANCE_KEY_FILE_NAME);
        this.profileId = profileId(this.workspaceDirectory);
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
        CompanionSessionDescriptor descriptor = this.activeDescriptor;
        if (descriptor == null
                || !ProcessHandle.of(descriptor.processId()).map(ProcessHandle::isAlive).orElse(false)) {
            throw new IOException("Companion is no longer running");
        }
        this.foregroundHandoff.transfer(descriptor.processId(), beforeTransfer, sendRequest);
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
                        CompanionProtocol.REQUESTED_CAPABILITIES,
                        CompanionAppClient.this.profileId,
                        CompanionAppClient.this.dataDirectory.toString(),
                        CompanionAppClient.this.runtimeClassIndex.indexFile().toString(),
                        CompanionAppClient.this.workspaceDirectory.toString(),
                        CompanionAppClient.this.runtimeClassIndex.runtimeSourceManifest().toString(),
                        CompanionAppClient.this.runtimeClassIndex.signature()
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
            resetConnection();
            CompanionSessionDescriptor descriptor = discoverOrStartCompanion();
            this.activeDescriptor = descriptor;
            this.sessionToken = readInstanceKey();
            InetSocketAddress address = sessionAddress(descriptor.port());
            reportProgress(CompanionStartupProgress.connecting());
            if (!this.client.connect(address)) {
                throw new IOException("Unable to connect to Companion at " + address);
            }
            CompletableFuture<Void> authentication = this.authenticated;
            readiness = this.ready;
            await(authentication, this.timeouts.handshake(), "Companion session handshake");
            await(readiness, this.timeouts.readiness(), "Companion UI readiness");
            reportProgress(CompanionStartupProgress.ready());
        } catch (IOException exception) {
            reportProgress(CompanionStartupProgress.failed(exception.getMessage()));
            this.client.close();
            throw exception;
        }
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
        Path launchJar = stageLaunchJar(this.appHome, installation.companionJar());
        Path logsDirectory = this.appHome.resolve("logs");
        Files.createDirectories(logsDirectory);
        this.processLog = logsDirectory.resolve(
                "companion-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss"))
                        + ".log"
        );
        Path javaExecutable = CompanionJavaRuntime.resolveCurrentExecutable();

        ProcessBuilder processBuilder = new ProcessBuilder(
                javaExecutable.toString(),
                "-jar",
                launchJar.toString(),
                CompanionLaunchContract.APP_HOME_ARGUMENT,
                this.appHome.toString()
        );
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(this.processLog.toFile());
        reportProgress(CompanionStartupProgress.starting());
        this.launchedProcess = processBuilder.start();
        TotalDebug.LOGGER.info(
                "Started TotalDebugCompanion from {} with Minecraft Java {}; output is written to {}",
                launchJar,
                javaExecutable,
                this.processLog
        );

        return awaitDescriptor(this.instanceDescriptorFile);
    }

    private CompanionSessionDescriptor readLiveDescriptor() throws IOException {
        if (!Files.isRegularFile(this.instanceDescriptorFile)) {
            return null;
        }
        CompanionSessionDescriptor descriptor = CompanionSessionDescriptor.read(this.instanceDescriptorFile);
        boolean processAlive = ProcessHandle.of(descriptor.processId()).map(ProcessHandle::isAlive).orElse(false);
        if (!processAlive) {
            Files.deleteIfExists(this.instanceDescriptorFile);
            Files.deleteIfExists(this.instanceKeyFile);
            return null;
        }
        if (descriptor.protocolVersion() != CompanionProtocol.VERSION) {
            throw new IOException(
                    "Close the running Companion before using protocol " + CompanionProtocol.VERSION
            );
        }
        if (!Files.isRegularFile(this.instanceKeyFile)) {
            throw new IOException("Companion instance key is missing");
        }
        return descriptor;
    }

    private String readInstanceKey() throws IOException {
        String token = Files.readString(this.instanceKeyFile, StandardCharsets.US_ASCII).trim();
        if (token.length() < 32) {
            throw new IOException("Companion instance key is invalid");
        }
        return token;
    }

    static Path stageLaunchJar(Path appHome, Path sourceJar) throws IOException {
        String hash = CompanionAppInstaller.sha256(sourceJar);
        Path buildDirectory = appHome.toAbsolutePath().normalize().resolve("builds").resolve(hash);
        Path launchJar = buildDirectory.resolve("TotalDebugCompanion.jar");
        if (Files.exists(launchJar)) {
            if (Files.isRegularFile(launchJar) && hash.equals(CompanionAppInstaller.sha256(launchJar))) {
                return launchJar;
            }
            throw new IOException("Companion build cache conflicts with " + launchJar);
        }
        Files.createDirectories(buildDirectory);
        Path staged = Files.createTempFile(buildDirectory, ".companion-", ".jar");
        try {
            Files.copy(sourceJar, staged, StandardCopyOption.REPLACE_EXISTING);
            if (!hash.equals(CompanionAppInstaller.sha256(staged))) {
                throw new IOException("Staged Companion checksum mismatch");
            }
            try {
                Files.move(staged, launchJar, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                if (!Files.isRegularFile(launchJar) || !hash.equals(CompanionAppInstaller.sha256(launchJar))) {
                    throw exception;
                }
            }
        } finally {
            Files.deleteIfExists(staged);
        }
        return launchJar;
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

    private void resetConnection() {
        this.transportExpected = false;
        this.client.close();
        this.authenticated = new CompletableFuture<>();
        this.ready = new CompletableFuture<>();
        this.negotiatedCapabilities = 0;
        this.sessionToken = null;
    }

    @Override
    public synchronized void close() {
        this.closing = true;
        notifySessionClosed();
        this.client.close();
    }

    private static String profileId(Path workspaceDirectory) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String identity = workspaceDirectory.toAbsolutePath().normalize().toString();
            if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
                identity = identity.toLowerCase(java.util.Locale.ROOT);
            }
            return HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
