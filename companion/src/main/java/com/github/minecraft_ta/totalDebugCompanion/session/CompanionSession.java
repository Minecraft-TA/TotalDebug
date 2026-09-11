package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import java.util.function.Consumer;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ProtocolBindings;
import com.github.minecraft_ta.totaldebug.storage.CompanionSessionDescriptor;
import com.github.minecraft_ta.totaldebug.storage.CompanionLaunchContract;
import com.github.minecraft_ta.totaldebug.protocol.scnet.FocusWindowMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReadyMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.OpenClassMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.DebugTargetMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RuntimeInventoryMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerManifestMessage;
import com.github.tth05.scnet.IConnectionListener;
import com.github.tth05.scnet.Server;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class CompanionSession implements AutoCloseable {
    private enum State {
        WAITING_FOR_HELLO,
        AUTHENTICATING,
        AUTHENTICATED,
        REJECTING,
        CLOSED
    }

    @FunctionalInterface
    public interface AttachmentHandler {
        void attach(ClientHelloMessage hello) throws IOException;
    }

    public interface Listener {
        default void openClass(OpenClassMessage message) { }

        default void focusWindow() { }

        default void connecting() {
        }

        default void connected() {
        }

        default void disconnected() {
        }

        default void runtimeInventory(RuntimeInventoryMessage message) {
        }

        default void serverManifest(ServerManifestMessage message) {}

        default void debugTarget(DebugTargetMessage message) {
        }
    }

    private final Server server = new Server();
    private final SessionAuthenticator authenticator;
    private final AttachmentHandler attachmentHandler;
    private final Listener listener;
    private final AtomicReference<State> state = new AtomicReference<>(State.WAITING_FOR_HELLO);
    private ProjectSelectionServer projectSelections;
    private AttachmentHandler projectSelectionHandler;

    public CompanionSession(String expectedToken) {
        this(expectedToken, hello -> { }, new Listener() { });
    }

    public CompanionSession(String expectedToken, AttachmentHandler attachmentHandler, Listener listener) {
        this.authenticator = new SessionAuthenticator(expectedToken);
        this.attachmentHandler = Objects.requireNonNull(attachmentHandler, "attachmentHandler");
        this.listener = Objects.requireNonNull(listener, "listener");
        configureTransport();
        registerMessages();
        registerHandlers();
    }

    public void bindAndPublish(CompanionLaunchConfiguration configuration) throws IOException {
        Objects.requireNonNull(configuration, "configuration");
        this.server.bind(sessionAddress(0));
        InetSocketAddress address = (InetSocketAddress) this.server.getLocalAddress();
        if (!address.getAddress().isLoopbackAddress()) {
            close();
            throw new IOException("Companion transport did not bind to loopback: " + address);
        }
        this.projectSelections = new ProjectSelectionServer(this.authenticator,
                this.projectSelectionHandler == null ? this.attachmentHandler : this.projectSelectionHandler, this::isConnected);
        new CompanionSessionDescriptor(CompanionProtocol.VERSION, address.getPort(), ProcessHandle.current().pid(), this.projectSelections.port())
                .writeAtomically(configuration.descriptorFile());
    }

    public void addExecutionResultListener(Consumer<ExecutionResultMessage> listener) {
        this.server.getMessageBus().listenAlways(ExecutionResultMessage.class, listener, listener);
    }

    public void removeExecutionResultListener(Consumer<ExecutionResultMessage> listener) {
        this.server.getMessageBus().unregister(ExecutionResultMessage.class, listener);
    }

    public void setProjectSelectionHandler(AttachmentHandler handler) {
        if (this.projectSelections != null) throw new IllegalStateException("Session is already published");
        this.projectSelectionHandler = Objects.requireNonNull(handler);
    }

    public void disconnect() {
        if (!this.server.isClientConnected()) return;
        try {
            this.server.closeClientAfterPendingWrites().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            this.server.closeClient();
        } catch (InterruptedException failure) {
            this.server.closeClient();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while disconnecting the previous game", failure);
        }
    }

    static InetSocketAddress sessionAddress(int port) {
        return new InetSocketAddress(CompanionLaunchContract.IPV4_LOOPBACK_HOST, port);
    }

    public Server server() {
        return this.server;
    }

    public boolean isConnected() {
        return this.state.get() == State.AUTHENTICATED && this.server.isClientConnected();
    }

    public boolean send(AbstractMessage message) {
        if (!isConnected()) {
            return false;
        }
        try {
            this.server.getMessageProcessor().enqueueMessage(Objects.requireNonNull(message, "message"));
            return true;
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            return false;
        }
    }

    private void configureTransport() {
        this.server.setMessageBus(new DefaultMessageBus() {
            @Override
            public void post(AbstractMessage message) {
                if (!(message instanceof ClientHelloMessage) && state.get() != State.AUTHENTICATED) {
                    rejectAndClose("Session authentication is required before " + message.getClass().getSimpleName());
                    return;
                }
                super.post(message);
            }
        });
        this.server.getMessageProcessor().setMaxFrameSize(DefaultMessageProcessor.DEFAULT_MAX_FRAME_SIZE);
        this.server.getMessageProcessor().setMaxStringLength(DefaultMessageProcessor.DEFAULT_MAX_STRING_LENGTH);
    }

    private void registerMessages() {
        ProtocolBindings.registerCompanion(this.server.getMessageProcessor());
    }

    private void registerHandlers() {
        this.server.getMessageBus().listenAlways(ClientHelloMessage.class, this::handleHello);
        this.server.getMessageBus().listenAlways(RuntimeInventoryMessage.class, this.listener::runtimeInventory);
        this.server.getMessageBus().listenAlways(ServerManifestMessage.class, this.listener::serverManifest);
        this.server.getMessageBus().listenAlways(DebugTargetMessage.class, this.listener::debugTarget);
        this.server.getMessageBus().listenAlways(OpenClassMessage.class, this.listener::openClass);
        this.server.getMessageBus().listenAlways(FocusWindowMessage.class, message ->
                SwingUtilities.invokeLater(this.listener::focusWindow));
        this.server.addConnectionListener(new IConnectionListener() {
            @Override
            public void onConnected() {
                if (CompanionSession.this.state.get() == State.WAITING_FOR_HELLO) {
                    CompanionSession.this.listener.connecting();
                }
            }

            @Override
            public void onDisconnected() {
                State previous = CompanionSession.this.state.getAndUpdate(state ->
                        state == State.CLOSED ? State.CLOSED : State.WAITING_FOR_HELLO
                );
                if (previous != State.CLOSED) {
                    CompanionSession.this.listener.disconnected();
                }
            }

            @Override
            public void onConnectionError(Throwable cause) {
                CompanionSession.this.listener.disconnected();
            }
        });
    }

    private void handleHello(ClientHelloMessage hello) {
        if (!this.state.compareAndSet(State.WAITING_FOR_HELLO, State.AUTHENTICATING)) {
            rejectAndClose("Handshake already completed");
            return;
        }

        ServerHelloMessage response = this.authenticator.authenticate(hello);
        if (!response.accepted()) {
            rejectAndClose(response.rejectionReason());
            return;
        }
        try {
            this.attachmentHandler.attach(hello);
        } catch (IOException | RuntimeException exception) {
            String message = exception.getMessage();
            rejectAndClose(message == null || message.isBlank() ? "Profile rejected" : message);
            return;
        }

        this.state.set(State.AUTHENTICATED);
        this.server.getMessageProcessor().enqueueMessage(response);
        this.server.getMessageProcessor().enqueueMessage(new ReadyMessage());
        this.listener.connected();
    }

    private void rejectAndClose(String reason) {
        State previous = this.state.getAndSet(State.REJECTING);
        if (previous == State.CLOSED || previous == State.REJECTING) {
            return;
        }
        this.server.getMessageProcessor().enqueueMessage(ServerHelloMessage.rejected(reason));
        this.server.closeClientAfterPendingWrites().whenComplete((ignored, failure) -> {
            this.state.compareAndSet(State.REJECTING, State.WAITING_FOR_HELLO);
        });
    }

    @Override
    public void close() {
        State previous = this.state.getAndSet(State.CLOSED);
        this.server.close();
        if (this.projectSelections != null) this.projectSelections.close();
        if (previous == State.AUTHENTICATED) {
            this.listener.disconnected();
        }
    }
}
