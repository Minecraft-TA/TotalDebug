package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.Side;
import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessages;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import java.util.function.BiConsumer;
import com.github.minecraft_ta.totaldebug.protocol.scnet.FromServerMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ProtocolBindings;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RelayFailedMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ToServerMessage;
import com.github.minecraft_ta.totaldebug.storage.CompanionSessionDescriptor;
import com.github.minecraft_ta.totaldebug.storage.CompanionLaunchContract;
import com.github.minecraft_ta.totaldebug.protocol.scnet.FocusWindowMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReadyMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.InspectSubjectMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ChangeResultMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.PackStackMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReloadResultMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.DebugTargetMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.PreparedFileMessage;
import com.github.minecraft_ta.totaldebug.protocol.message.PreparedFilePayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.PlayingMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerScriptsMessage;
import com.github.tth05.scnet.IConnectionListener;
import com.github.tth05.scnet.Server;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class CompanionSession implements AutoCloseable {
    private final List<BiConsumer<Side, ExecutionResultMessage>> serverResultListeners = new CopyOnWriteArrayList<>();
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

        default void inspectSubject(InspectSubjectMessage message) { }

        /** The game answered a change of values it keeps. */
        default void changeResult(ChangeResultMessage message) { }

        default void packStack(PackStackMessage message) { }

        default void reloadResult(ReloadResultMessage message) { }

        default void focusWindow() { }

        default void connecting() {
        }

        default void connected() {
        }

        /** Reports the number of the authenticated connection that ended, as returned by {@link CompanionSession#connection()}. */
        default void disconnected(long connection) {
        }

        default void failed(String detail, ClientHelloMessage hello) { }

        /** The state of a file the game prepares for Companion: its runtime inventory, pack catalog or item icons. */
        default void preparedFile(PreparedFilePayload file) {
        }

        /** The game's server answered whether it runs this player's scripts. */
        default void serverScripts(ServerScriptsMessage message) {}
        /** The game client could not carry a message to the server; {@code correlation} is the message's. */
        default void relayFailed(RelayFailedMessage message) {}
        default void playing(PlayingMessage message) {}

        default void debugTarget(DebugTargetMessage message) {
        }
    }

    private final Server server = new Server();
    private final SessionAuthenticator authenticator;
    private final AttachmentHandler attachmentHandler;
    private final Listener listener;
    private final AtomicReference<State> state = new AtomicReference<>(State.WAITING_FOR_HELLO);
    private final AtomicLong connections = new AtomicLong();
    /** Held while a connection is numbered and taken as authenticated, and by sends bound to one connection. */
    private final Object connectionChange = new Object();
    private ProjectSelectionServer projectSelections;
    private AttachmentHandler projectSelectionHandler;
    private CompanionSessionDescriptor descriptor;
    private Path descriptorFile;
    private volatile ClientHelloMessage clientHello;

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
        this.descriptorFile = configuration.descriptorFile();
        this.descriptor = new CompanionSessionDescriptor(CompanionProtocol.VERSION, address.getPort(), ProcessHandle.current().pid(), this.projectSelections.port(), null);
        publishProfile(null);
    }

    /** Writes only discovery metadata; the application's profile admission remains authoritative. */
    public synchronized void publishProfile(String profileId) throws IOException {
        if (state.get() == State.CLOSED) throw new IOException("Companion session is closed");
        if (descriptor == null) return;
        var next = new CompanionSessionDescriptor(descriptor.protocolVersion(), descriptor.port(), descriptor.processId(), descriptor.projectPort(), profileId);
        next.writeAtomically(descriptorFile);
        descriptor = next;
    }

    /** Receives script results with the side that sent them: the game client, or the server through the relay. */
    public void addExecutionResultListener(BiConsumer<Side, ExecutionResultMessage> listener) {
        this.server.getMessageBus().listenAlways(ExecutionResultMessage.class, listener,
                message -> listener.accept(Side.CLIENT, message));
        this.serverResultListeners.add(listener);
    }

    public void removeExecutionResultListener(BiConsumer<Side, ExecutionResultMessage> listener) {
        this.server.getMessageBus().unregister(ExecutionResultMessage.class, listener);
        this.serverResultListeners.remove(listener);
    }

    public void setProjectSelectionHandler(AttachmentHandler handler) {
        if (this.projectSelections != null) throw new IllegalStateException("Session is already published");
        this.projectSelectionHandler = Objects.requireNonNull(handler);
    }

    public void disconnect() {
        if (!this.server.isClientConnected()) return;
        try {
            this.server.closeClientAfterPendingWrites().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException failure) {
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

    /** Numbers authenticated connections; it changes before a new connection reports itself connected. */
    public long connection() {
        return this.connections.get();
    }

    public boolean isConnected() {
        return this.state.get() == State.AUTHENTICATED && this.server.isClientConnected();
    }

    /** Includes a client whose authentication handshake is still in progress. */
    public boolean hasClient() {
        return this.server.isClientConnected();
    }

    /**
     * Sends {@code message} only while {@code connection} is the authenticated one, checked and queued in one step, so it
     * never reaches a game that connected after it.
     */
    public boolean send(long connection, AbstractMessage message) {
        synchronized (this.connectionChange) {
            return this.connections.get() == connection && send(message);
        }
    }

    /**
     * Sends {@code message} to the game's server through the game client, which carries it unread (see
     * {@code docs/MOD_SIDES.md}). {@code correlation} is the request's id, such as a script run, which a failure to
     * deliver names; {@code world} is the world the message is only valid in, as {@code PLAYING} names it, or empty.
     */
    public boolean sendToServer(AbstractMessage message, int correlation, String world) {
        return send(new ToServerMessage(RelayedMessages.toServer(message, correlation, world)));
    }

    public boolean send(AbstractMessage message) {
        if (!isConnected()) {
            return false;
        }
        try {
            this.server.getMessageProcessor().enqueueMessage(Objects.requireNonNull(message, "message"));
            return true;
        } catch (RejectedExecutionException rejected) {
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
        this.server.getMessageBus().listenAlways(PreparedFileMessage.class, message -> this.listener.preparedFile(message.payload()));
        this.server.getMessageBus().listenAlways(ServerScriptsMessage.class, this.listener::serverScripts);
        this.server.getMessageBus().listenAlways(RelayFailedMessage.class, this.listener::relayFailed);
        // The server's messages arrive through the game client and reach the same listeners as the game's own.
        this.server.getMessageBus().listenAlways(FromServerMessage.class, message -> {
            AbstractMessage unwrapped;
            try {
                unwrapped = RelayedMessages.decodeFromServer(message.payload());
            } catch (RuntimeException invalid) {
                System.getLogger(CompanionSession.class.getName()).log(System.Logger.Level.WARNING,
                        "Discarding a message from the server that could not be read", invalid);
                return;
            }
            if (unwrapped instanceof ExecutionResultMessage result) {
                for (var resultListener : this.serverResultListeners) resultListener.accept(Side.SERVER, result);
            } else {
                this.server.getMessageBus().post(unwrapped);
            }
        });
        this.server.getMessageBus().listenAlways(PlayingMessage.class, this.listener::playing);
        this.server.getMessageBus().listenAlways(DebugTargetMessage.class, this.listener::debugTarget);
        this.server.getMessageBus().listenAlways(InspectSubjectMessage.class, this.listener::inspectSubject);
        this.server.getMessageBus().listenAlways(ChangeResultMessage.class, this.listener::changeResult);
        this.server.getMessageBus().listenAlways(PackStackMessage.class, this.listener::packStack);
        this.server.getMessageBus().listenAlways(ReloadResultMessage.class, this.listener::reloadResult);
        this.server.getMessageBus().listenAlways(FocusWindowMessage.class, message ->
                SwingUtilities.invokeLater(this.listener::focusWindow));
        this.server.addConnectionListener(new IConnectionListener() {
            @Override
            public void onConnected() {
                clientHello = null;
                if (CompanionSession.this.state.get() == State.WAITING_FOR_HELLO) {
                    CompanionSession.this.listener.connecting();
                }
            }

            @Override
            public void onDisconnected() {
                // Read before leaving AUTHENTICATED; a replacement cannot authenticate until then.
                long ended = CompanionSession.this.connections.get();
                State previous = CompanionSession.this.state.getAndUpdate(state ->
                        state == State.CLOSED ? State.CLOSED : State.WAITING_FOR_HELLO
                );
                if (previous != State.CLOSED) {
                    CompanionSession.this.listener.disconnected(ended);
                }
            }

            @Override
            public void onConnectionError(Throwable cause) {
                CompanionSession.this.listener.disconnected(CompanionSession.this.connections.get());
                CompanionSession.this.listener.failed("Minecraft connection failed: " + cause.getMessage(), clientHello);
            }
        });
    }

    private void handleHello(ClientHelloMessage hello) {
        if (!this.state.compareAndSet(State.WAITING_FOR_HELLO, State.AUTHENTICATING)) {
            rejectAndClose("Handshake already completed");
            return;
        }

        this.clientHello = hello;

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

        synchronized (this.connectionChange) {
            this.connections.incrementAndGet();
            this.state.set(State.AUTHENTICATED);
        }
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
        this.listener.failed(reason, clientHello);
        this.server.closeClientAfterPendingWrites().whenComplete((ignored, failure) -> {
            this.state.compareAndSet(State.REJECTING, State.WAITING_FOR_HELLO);
        });
    }

    @Override
    public synchronized void close() {
        long ended = this.connections.get();
        State previous = this.state.getAndSet(State.CLOSED);
        this.server.close();
        if (this.projectSelections != null) this.projectSelections.close();
        if (previous == State.AUTHENTICATED) {
            this.listener.disconnected(ended);
        }
    }
}
