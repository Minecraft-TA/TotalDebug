package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totaldebug.storage.CompanionSessionDescriptor;

import com.github.minecraft_ta.totaldebug.storage.CompanionLaunchContract;

import com.github.minecraft_ta.totalDebugCompanion.messages.FocusWindowMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.ReadyMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.codeView.OpenClassMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.debugger.DebugTargetMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.RunScriptMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.ExecutionResultMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.StopScriptMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.session.ClientHelloMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.session.ServerHelloMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.session.RuntimeInventoryMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.session.RetryRuntimeInventoryMessage;
import com.github.tth05.scnet.IConnectionListener;
import com.github.tth05.scnet.Server;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;

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
        void attach(ClientHelloMessage hello, long capabilities) throws IOException;
    }

    public interface Listener {
        default void connecting() {
        }

        default void connected(long capabilities) {
        }

        default void disconnected() {
        }

        default void runtimeInventory(RuntimeInventoryMessage message) {
        }

        default void debugTarget(DebugTargetMessage message) {
        }
    }

    private final Server server = new Server();
    private final SessionAuthenticator authenticator;
    private final AttachmentHandler attachmentHandler;
    private final Listener listener;
    private final AtomicReference<State> state = new AtomicReference<>(State.WAITING_FOR_HELLO);
    private volatile long capabilities;

    public CompanionSession(String expectedToken) {
        this(expectedToken, (hello, capabilities) -> { }, new Listener() { });
    }

    public CompanionSession(String expectedToken, AttachmentHandler attachmentHandler, Listener listener) {
        this.authenticator = new SessionAuthenticator(expectedToken, CompanionProtocol.SUPPORTED_CAPABILITIES);
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
        new CompanionSessionDescriptor(CompanionProtocol.VERSION, address.getPort(), ProcessHandle.current().pid())
                .writeAtomically(configuration.descriptorFile());
    }

    static InetSocketAddress sessionAddress(int port) {
        return new InetSocketAddress(CompanionLaunchContract.IPV4_LOOPBACK_HOST, port);
    }

    public Server server() {
        return this.server;
    }

    public boolean hasCapability(long capability) {
        return this.state.get() == State.AUTHENTICATED
                && (this.capabilities & capability) == capability;
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
        this.server.getMessageProcessor().setMaxFrameSize(DefaultMessageProcessor.DEFAULT_MAX_FRAME_SIZE);
        this.server.getMessageProcessor().setMaxStringLength(DefaultMessageProcessor.DEFAULT_MAX_STRING_LENGTH);
    }

    private void registerMessages() {
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.READY, ReadyMessage.class);
        this.server.getMessageProcessor().registerMessage(
                CompanionProtocol.OPEN_CLASS,
                OpenClassMessage.class,
                OpenClassMessage::new
        );
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.RUN_SCRIPT, RunScriptMessage.class);
        this.server.getMessageProcessor().registerMessage(
                CompanionProtocol.EXECUTION_RESULT,
                ExecutionResultMessage.class,
                ExecutionResultMessage::new
        );
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.STOP_SCRIPT, StopScriptMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.FOCUS_WINDOW, FocusWindowMessage.class, FocusWindowMessage::new);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.CLIENT_HELLO, ClientHelloMessage.class, ClientHelloMessage::new);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.SERVER_HELLO, ServerHelloMessage.class);
        this.server.getMessageProcessor().registerMessage(
                CompanionProtocol.RUNTIME_INVENTORY,
                RuntimeInventoryMessage.class,
                RuntimeInventoryMessage::new
        );
        this.server.getMessageProcessor().registerMessage(
                CompanionProtocol.RETRY_RUNTIME_INVENTORY,
                RetryRuntimeInventoryMessage.class
        );
        this.server.getMessageProcessor().registerMessage(
                CompanionProtocol.DEBUG_TARGET,
                DebugTargetMessage.class,
                DebugTargetMessage::new
        );
    }

    private void registerHandlers() {
        this.server.getMessageBus().listenAlways(ClientHelloMessage.class, this::handleHello);
        this.server.getMessageBus().listenAlways(RuntimeInventoryMessage.class, message -> runFeature(
                CompanionProtocol.CAPABILITY_RUNTIME_INVENTORY,
                "RuntimeInventory",
                () -> this.listener.runtimeInventory(message)
        ));
        this.server.getMessageBus().listenAlways(DebugTargetMessage.class, message -> runFeature(
                CompanionProtocol.CAPABILITY_DEBUGGER,
                "DebugTarget",
                () -> this.listener.debugTarget(message)
        ));
        this.server.getMessageBus().listenAlways(OpenClassMessage.class, message -> runFeature(
                CompanionProtocol.CAPABILITY_CODE_VIEW,
                "OpenClass",
                () -> OpenClassMessage.handle(message)
        ));
        this.server.getMessageBus().listenAlways(FocusWindowMessage.class, message -> runFeature(
                CompanionProtocol.CAPABILITY_FOCUS_WINDOW,
                "FocusWindow",
                () -> SwingUtilities.invokeLater(com.github.minecraft_ta.totalDebugCompanion.CompanionApp::focusWindow)
        ));
        guardFeature(RunScriptMessage.class, CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION, "RunScript");
        guardFeature(ExecutionResultMessage.class, CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION, "ExecutionResult");
        guardFeature(StopScriptMessage.class, CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION, "StopScript");
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
                CompanionSession.this.capabilities = 0;
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
            this.attachmentHandler.attach(hello, response.capabilities());
        } catch (IOException | RuntimeException exception) {
            String message = exception.getMessage();
            rejectAndClose(message == null || message.isBlank() ? "Profile rejected" : message);
            return;
        }

        this.capabilities = response.capabilities();
        this.state.set(State.AUTHENTICATED);
        this.server.getMessageProcessor().enqueueMessage(response);
        this.server.getMessageProcessor().enqueueMessage(new ReadyMessage());
        this.listener.connected(this.capabilities);
    }

    private void runFeature(long capability, String featureName, Runnable operation) {
        if (!hasCapability(capability)) {
            rejectAndClose(featureName + " is unavailable");
            return;
        }
        operation.run();
    }

    private <T extends com.github.tth05.scnet.message.AbstractMessage> void guardFeature(
            Class<T> messageClass,
            long capability,
            String featureName
    ) {
        this.server.getMessageBus().listenAlways(messageClass, message -> runFeature(capability, featureName, () -> { }));
    }

    private void rejectAndClose(String reason) {
        State previous = this.state.getAndSet(State.REJECTING);
        if (previous == State.CLOSED || previous == State.REJECTING) {
            return;
        }
        this.server.getMessageProcessor().enqueueMessage(ServerHelloMessage.rejected(reason));
        this.server.closeClientAfterPendingWrites().whenComplete((ignored, failure) -> {
            this.capabilities = 0;
            this.state.compareAndSet(State.REJECTING, State.WAITING_FOR_HELLO);
        });
    }

    @Override
    public void close() {
        State previous = this.state.getAndSet(State.CLOSED);
        this.capabilities = 0;
        this.server.close();
        if (previous == State.AUTHENTICATED) {
            this.listener.disconnected();
        }
    }
}
