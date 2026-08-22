package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.companion.message.CompanionReadyMessage;
import com.github.minecraft_ta.totaldebug.client.companion.message.DecompileOrOpenMessage;
import com.github.minecraft_ta.totaldebug.client.companion.message.FocusWindowMessage;
import com.github.tth05.scnet.Client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class CompanionAppClient implements AutoCloseable {
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 25570);
    private static final int CONNECT_TIMEOUT_SECONDS = 60;
    private static final int READY_TIMEOUT_SECONDS = 60;

    private final Path dataDirectory;
    private final Path appDirectory;
    private final CompanionAppInstaller installer;
    private final RuntimeClassIndex runtimeClassIndex;
    private final Client client = new Client();
    private final AtomicReference<CompletableFuture<Void>> ready = new AtomicReference<>(new CompletableFuture<>());
    private final AtomicReference<Consumer<DecompileOrOpenMessage>> decompileRequestHandler =
            new AtomicReference<>(message -> TotalDebug.LOGGER.warn(
                    "Ignoring companion decompile request for {} because no handler is installed",
                    message.name()
            ));

    private Process process;
    private Path processLog;

    public CompanionAppClient(Path totalDebugDirectory) {
        Path root = Objects.requireNonNull(totalDebugDirectory, "totalDebugDirectory").toAbsolutePath().normalize();
        this.dataDirectory = root.resolve("data");
        this.appDirectory = root.resolve("companion-app");
        this.installer = new CompanionAppInstaller(this.appDirectory);
        this.runtimeClassIndex = new RuntimeClassIndex(this.dataDirectory);

        this.client.getMessageProcessor().registerMessage(
                (short) 1,
                CompanionReadyMessage.class,
                CompanionReadyMessage::new
        );
        this.client.getMessageProcessor().registerMessage(
                (short) 2,
                DecompileOrOpenMessage.class,
                DecompileOrOpenMessage::new
        );
        this.client.getMessageProcessor().registerMessage((short) 11, FocusWindowMessage.class);
        this.client.getMessageBus().listenAlways(
                CompanionReadyMessage.class,
                message -> this.ready.get().complete(null)
        );
        this.client.getMessageBus().listenAlways(
                DecompileOrOpenMessage.class,
                message -> this.decompileRequestHandler.get().accept(message)
        );

        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "TotalDebug companion shutdown"));
    }

    public void setDecompileRequestHandler(Consumer<DecompileOrOpenMessage> handler) {
        this.decompileRequestHandler.set(Objects.requireNonNull(handler, "handler"));
    }

    public synchronized void open(Path sourceFile, int targetType, String targetIdentifier) throws IOException {
        ensureConnectedAndReady();
        this.client.getMessageProcessor().enqueueMessage(new DecompileOrOpenMessage(
                sourceFile.toAbsolutePath().normalize().toString(),
                targetType,
                targetIdentifier
        ));
    }

    public synchronized void focus() throws IOException {
        ensureConnectedAndReady();
        this.client.getMessageProcessor().enqueueMessage(new FocusWindowMessage());
    }

    public Path dataDirectory() {
        return this.dataDirectory;
    }

    private void ensureConnectedAndReady() throws IOException {
        this.runtimeClassIndex.ensurePresent();
        if (this.client.isConnected() && this.ready.get().isDone()) {
            return;
        }

        CompletableFuture<Void> readyFuture = new CompletableFuture<>();
        this.ready.set(readyFuture);
        if (!connectOnce()) {
            startInstalledCompanion();
            connectAfterStart();
        }

        try {
            readyFuture.get(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the companion UI", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IOException("Companion UI did not become ready within " + READY_TIMEOUT_SECONDS + " seconds", exception);
        }
    }

    private boolean connectOnce() {
        try {
            return this.client.connect(ADDRESS);
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.debug("Companion is not accepting connections on {}", ADDRESS, exception);
            return false;
        }
    }

    private void connectAfterStart() throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONNECT_TIMEOUT_SECONDS);
        do {
            if (connectOnce()) {
                return;
            }
            if (this.process != null && !this.process.isAlive()) {
                throw new IOException("Companion process exited before connecting; see " + this.processLog);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while connecting to the companion app", exception);
            }
        } while (System.nanoTime() < deadline);
        throw new IOException(
                "Companion did not accept connections on " + ADDRESS
                        + " within " + CONNECT_TIMEOUT_SECONDS + " seconds"
        );
    }

    private void startInstalledCompanion() throws IOException {
        if (this.process != null && this.process.isAlive()) {
            return;
        }

        CompanionInstallation installation;
        try {
            installation = this.installer.resolveOrInstall();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while installing the companion app", exception);
        }

        Files.createDirectories(this.dataDirectory);
        Path logsDirectory = this.appDirectory.resolve("logs");
        Files.createDirectories(logsDirectory);
        this.processLog = logsDirectory.resolve(
                "companion-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss")) + ".log"
        );
        Path javaExecutable = CompanionJavaRuntime.resolveCurrentExecutable();

        ProcessBuilder processBuilder = new ProcessBuilder(
                javaExecutable.toString(),
                "-jar",
                installation.companionJar().toString(),
                this.dataDirectory.toString()
        );
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(this.processLog.toFile());
        this.process = processBuilder.start();
        TotalDebug.LOGGER.info(
                "Started TotalDebugCompanion from {} with Minecraft Java {}; output is written to {}",
                installation.companionJar(),
                javaExecutable,
                this.processLog
        );
    }

    @Override
    public synchronized void close() {
        this.client.close();
        if (this.process != null && this.process.isAlive()) {
            this.process.destroy();
        }
    }
}
