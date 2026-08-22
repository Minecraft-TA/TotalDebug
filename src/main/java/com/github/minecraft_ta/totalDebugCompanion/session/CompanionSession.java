package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totalDebugCompanion.messages.FocusWindowMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.ReadyMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.chunkGrid.ChunkGridDataMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.chunkGrid.ChunkGridRequestInfoUpdateMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.chunkGrid.ReceiveDataStateMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.chunkGrid.UpdateFollowPlayerStateMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.codeView.DecompileOrOpenMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.packetLogger.BlockPacketMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.packetLogger.CapturePacketMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.packetLogger.ChannelListMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.packetLogger.ClearPacketsMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.packetLogger.IncomingPacketsMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.packetLogger.OutgoingPacketsMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.packetLogger.PacketContentMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.packetLogger.PacketLoggerStateChangeMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.packetLogger.SetChannelMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.RunScriptMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.ScriptStatusMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.StopScriptMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.search.OpenSearchResultsMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.session.ClientHelloMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.session.ServerHelloMessage;
import com.github.tth05.scnet.IConnectionListener;
import com.github.tth05.scnet.Server;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class CompanionSession implements AutoCloseable {
    private enum State {
        WAITING_FOR_HELLO,
        AUTHENTICATED,
        REJECTING,
        CLOSED
    }

    private final Server server = new Server();
    private final SessionAuthenticator authenticator;
    private final CompletableFuture<Long> authentication = new CompletableFuture<>();
    private final CompletableFuture<Void> authenticatedDisconnect = new CompletableFuture<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.WAITING_FOR_HELLO);
    private volatile long capabilities;

    public CompanionSession(String expectedToken) {
        this.authenticator = new SessionAuthenticator(expectedToken, CompanionProtocol.SUPPORTED_CAPABILITIES);
        configureTransport();
        registerMessages();
        registerHandlers();
    }

    public void bindAndPublish(CompanionLaunchConfiguration configuration) throws IOException {
        Objects.requireNonNull(configuration, "configuration");
        this.server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        InetSocketAddress address = (InetSocketAddress) this.server.getLocalAddress();
        if (!address.getAddress().isLoopbackAddress()) {
            close();
            throw new IOException("Companion transport did not bind to a loopback address: " + address);
        }
        new CompanionSessionDescriptor(CompanionProtocol.VERSION, address.getPort(), ProcessHandle.current().pid())
                .writeAtomically(configuration.sessionDescriptor());
    }

    public Server server() {
        return this.server;
    }

    public long awaitAuthentication(int timeoutSeconds) throws IOException {
        try {
            return this.authentication.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the Minecraft session handshake", exception);
        } catch (ExecutionException exception) {
            throw new IOException("Minecraft session handshake failed", exception.getCause());
        } catch (TimeoutException exception) {
            throw new IOException(
                    "Minecraft did not authenticate within " + timeoutSeconds + " seconds",
                    exception
            );
        }
    }

    public boolean hasCapability(long capability) {
        return this.state.get() == State.AUTHENTICATED
                && (this.capabilities & capability) == capability;
    }

    public boolean markUiReady() {
        State currentState = this.state.get();
        if (currentState == State.CLOSED && this.authenticatedDisconnect.isDone()) {
            return false;
        }
        if (currentState != State.AUTHENTICATED) {
            throw new IllegalStateException("Cannot send Ready before authentication succeeds");
        }
        this.server.getMessageProcessor().enqueueMessage(new ReadyMessage());
        return true;
    }

    public void awaitAuthenticatedDisconnect() throws IOException {
        try {
            this.authenticatedDisconnect.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Minecraft to disconnect", exception);
        } catch (ExecutionException exception) {
            throw new IOException("Authenticated Minecraft session ended with an error", exception.getCause());
        }
    }

    void awaitAuthenticatedDisconnect(int timeoutSeconds) throws IOException {
        try {
            this.authenticatedDisconnect.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Minecraft to disconnect", exception);
        } catch (ExecutionException exception) {
            throw new IOException("Authenticated Minecraft session ended with an error", exception.getCause());
        } catch (TimeoutException exception) {
            throw new IOException("Minecraft did not disconnect within " + timeoutSeconds + " seconds", exception);
        }
    }

    private void configureTransport() {
        this.server.getMessageProcessor().setMaxFrameSize(DefaultMessageProcessor.RECOMMENDED_MAX_FRAME_SIZE);
        this.server.getMessageProcessor().setMaxStringLength(DefaultMessageProcessor.RECOMMENDED_MAX_STRING_LENGTH);
    }

    private void registerMessages() {
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.READY, ReadyMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.DECOMPILE_OR_OPEN, DecompileOrOpenMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.OPEN_SEARCH_RESULTS, OpenSearchResultsMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.RECEIVE_DATA_STATE, ReceiveDataStateMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.CHUNK_GRID_DATA, ChunkGridDataMessage.class);
        this.server.getMessageProcessor().registerMessage(
                CompanionProtocol.CHUNK_GRID_REQUEST_INFO_UPDATE,
                ChunkGridRequestInfoUpdateMessage.class
        );
        this.server.getMessageProcessor().registerMessage(
                CompanionProtocol.UPDATE_FOLLOW_PLAYER_STATE,
                UpdateFollowPlayerStateMessage.class
        );
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.RUN_SCRIPT, RunScriptMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.SCRIPT_STATUS, ScriptStatusMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.STOP_SCRIPT, StopScriptMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.FOCUS_WINDOW, FocusWindowMessage.class);
        this.server.getMessageProcessor().registerMessage(
                CompanionProtocol.PACKET_LOGGER_STATE_CHANGE,
                PacketLoggerStateChangeMessage.class
        );
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.INCOMING_PACKETS, IncomingPacketsMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.OUTGOING_PACKETS, OutgoingPacketsMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.CLEAR_PACKETS, ClearPacketsMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.CHANNEL_LIST, ChannelListMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.SET_CHANNEL, SetChannelMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.PACKET_CONTENT, PacketContentMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.CAPTURE_PACKET, CapturePacketMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.BLOCK_PACKET, BlockPacketMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.CLIENT_HELLO, ClientHelloMessage.class);
        this.server.getMessageProcessor().registerMessage(CompanionProtocol.SERVER_HELLO, ServerHelloMessage.class);
    }

    private void registerHandlers() {
        this.server.getMessageBus().listenAlways(ClientHelloMessage.class, this::handleHello);
        this.server.getMessageBus().listenAlways(DecompileOrOpenMessage.class, message -> runFeature(
                CompanionProtocol.CAPABILITY_CODE_VIEW,
                "DecompileOrOpen",
                () -> DecompileOrOpenMessage.handle(message)
        ));
        this.server.getMessageBus().listenAlways(OpenSearchResultsMessage.class, message -> runFeature(
                CompanionProtocol.CAPABILITY_SEARCH_RESULTS,
                "OpenSearchResults",
                () -> SwingUtilities.invokeLater(() -> OpenSearchResultsMessage.handle(message))
        ));
        this.server.getMessageBus().listenAlways(FocusWindowMessage.class, message -> runFeature(
                CompanionProtocol.CAPABILITY_FOCUS_WINDOW,
                "FocusWindow",
                () -> SwingUtilities.invokeLater(com.github.minecraft_ta.totalDebugCompanion.CompanionApp::focusWindow)
        ));
        guardFeature(ReceiveDataStateMessage.class, CompanionProtocol.CAPABILITY_CHUNK_GRID, "ReceiveDataState");
        guardFeature(ChunkGridDataMessage.class, CompanionProtocol.CAPABILITY_CHUNK_GRID, "ChunkGridData");
        guardFeature(
                ChunkGridRequestInfoUpdateMessage.class,
                CompanionProtocol.CAPABILITY_CHUNK_GRID,
                "ChunkGridRequestInfoUpdate"
        );
        guardFeature(
                UpdateFollowPlayerStateMessage.class,
                CompanionProtocol.CAPABILITY_CHUNK_GRID,
                "UpdateFollowPlayerState"
        );
        guardFeature(RunScriptMessage.class, CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION, "RunScript");
        guardFeature(ScriptStatusMessage.class, CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION, "ScriptStatus");
        guardFeature(StopScriptMessage.class, CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION, "StopScript");
        guardFeature(
                PacketLoggerStateChangeMessage.class,
                CompanionProtocol.CAPABILITY_PACKET_LOGGER,
                "PacketLoggerStateChange"
        );
        guardFeature(IncomingPacketsMessage.class, CompanionProtocol.CAPABILITY_PACKET_LOGGER, "IncomingPackets");
        guardFeature(OutgoingPacketsMessage.class, CompanionProtocol.CAPABILITY_PACKET_LOGGER, "OutgoingPackets");
        guardFeature(ClearPacketsMessage.class, CompanionProtocol.CAPABILITY_PACKET_LOGGER, "ClearPackets");
        guardFeature(ChannelListMessage.class, CompanionProtocol.CAPABILITY_PACKET_LOGGER, "ChannelList");
        guardFeature(SetChannelMessage.class, CompanionProtocol.CAPABILITY_PACKET_LOGGER, "SetChannel");
        guardFeature(PacketContentMessage.class, CompanionProtocol.CAPABILITY_PACKET_LOGGER, "PacketContent");
        guardFeature(CapturePacketMessage.class, CompanionProtocol.CAPABILITY_PACKET_LOGGER, "CapturePacket");
        guardFeature(BlockPacketMessage.class, CompanionProtocol.CAPABILITY_PACKET_LOGGER, "BlockPacket");
        this.server.addConnectionListener(new IConnectionListener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onDisconnected() {
                if (CompanionSession.this.state.compareAndSet(State.WAITING_FOR_HELLO, State.CLOSED)) {
                    CompanionSession.this.authentication.completeExceptionally(
                            new IOException("Minecraft disconnected before authenticating")
                    );
                    return;
                }
                if (CompanionSession.this.state.compareAndSet(State.AUTHENTICATED, State.CLOSED)) {
                    CompanionSession.this.authenticatedDisconnect.complete(null);
                }
            }

            @Override
            public void onConnectionError(Throwable cause) {
                CompanionSession.this.authentication.completeExceptionally(cause);
            }
        });
    }

    private void handleHello(ClientHelloMessage hello) {
        if (this.state.get() != State.WAITING_FOR_HELLO) {
            rejectAndClose("Handshake already completed");
            return;
        }

        ServerHelloMessage response = this.authenticator.authenticate(hello);
        this.server.getMessageProcessor().enqueueMessage(response);
        if (!response.accepted()) {
            this.state.set(State.REJECTING);
            closeAfterRejection(response.rejectionReason(), false);
            return;
        }
        if (!this.state.compareAndSet(State.WAITING_FOR_HELLO, State.AUTHENTICATED)) {
            rejectAndClose("Session state changed during authentication");
            return;
        }

        this.capabilities = response.capabilities();
        this.authentication.complete(this.capabilities);
    }

    private void runFeature(long capability, String featureName, Runnable operation) {
        if (!hasCapability(capability)) {
            rejectAndClose(featureName + " was sent before its capability was negotiated");
            return;
        }
        operation.run();
    }

    private <T extends com.github.tth05.scnet.message.AbstractMessage> void guardFeature(
            Class<T> messageClass,
            long capability,
            String featureName
    ) {
        this.server.getMessageBus().listenAlways(
                messageClass,
                message -> runFeature(capability, featureName, () -> { })
        );
    }

    private void rejectAndClose(String reason) {
        State previous = this.state.getAndSet(State.REJECTING);
        if (previous == State.CLOSED || previous == State.REJECTING) {
            return;
        }
        this.server.getMessageProcessor().enqueueMessage(ServerHelloMessage.rejected(reason));
        closeAfterRejection(reason, previous == State.AUTHENTICATED);
    }

    private void closeAfterRejection(String reason, boolean authenticatedSession) {
        this.server.closeClientAfterPendingWrites().whenComplete((ignored, failure) -> {
            this.state.set(State.CLOSED);
            IOException exception = failure == null
                    ? new IOException(reason)
                    : new IOException(reason, failure);
            if (authenticatedSession) {
                this.authenticatedDisconnect.completeExceptionally(exception);
            } else {
                this.authentication.completeExceptionally(exception);
            }
        });
    }

    @Override
    public void close() {
        this.state.set(State.CLOSED);
        this.server.close();
        this.authentication.completeExceptionally(new IOException("Companion session closed"));
    }
}
