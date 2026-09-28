package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The mod's two NeoForge payloads, which carry the relay between the game client and the server (see
 * {@code docs/MOD_SIDES.md}). Every server operation travels through them.
 */
public final class TotalDebugNetwork {
    public static final String PROTOCOL_VERSION = "7";

    private volatile Consumer<RelayChunk> companionReceiver = chunk -> TotalDebug.LOGGER.warn(
            "Discarding a relayed message {} because the client's relay is not ready", chunk.messageId());

    public TotalDebugNetwork(IEventBus modEventBus) {
        Objects.requireNonNull(modEventBus, "modEventBus").addListener(this::registerPayloads);
    }

    /** Hands the pieces the server sends for Companion to the client's end of the relay. */
    public void setCompanionReceiver(Consumer<RelayChunk> receiver) {
        this.companionReceiver = Objects.requireNonNull(receiver, "receiver");
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.playToServer(ToServerPayload.TYPE, ToServerPayload.STREAM_CODEC,
                (payload, context) -> TotalDebug.get().serverRelay().receive((ServerPlayer) context.player(), payload.chunk()));
        registrar.playToClient(ToCompanionPayload.TYPE, ToCompanionPayload.STREAM_CODEC,
                (payload, context) -> this.companionReceiver.accept(payload.chunk()));
    }
}
