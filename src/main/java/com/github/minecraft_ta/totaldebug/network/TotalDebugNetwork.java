package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Objects;
import java.util.function.Consumer;

public final class TotalDebugNetwork {
    public static final String PROTOCOL_VERSION = "1";

    private final ForwardedCompanionPayloadSink forwardedCompanionPayloads = new ForwardedCompanionPayloadSink();

    public TotalDebugNetwork(IEventBus modEventBus) {
        Objects.requireNonNull(modEventBus, "modEventBus").addListener(this::registerPayloads);
    }

    public AutoCloseable installForwardedCompanionReceiver(Consumer<ForwardedCompanionPayload> receiver) {
        return this.forwardedCompanionPayloads.install(receiver);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.playToClient(
                ForwardedCompanionPayload.TYPE,
                ForwardedCompanionPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (!this.forwardedCompanionPayloads.deliver(payload)) {
                        TotalDebug.LOGGER.warn(
                                "Discarding forwarded companion message {} because the companion receiver is not active",
                                payload.messageId()
                        );
                    }
                }
        );
        registrar.playToServer(
                RunServerScriptPayload.TYPE,
                RunServerScriptPayload.STREAM_CODEC,
                (payload, context) -> TotalDebug.get().serverScripts().runScript(
                        (ServerPlayer) context.player(),
                        payload
                )
        );
        registrar.playToServer(
                StopServerScriptPayload.TYPE,
                StopServerScriptPayload.STREAM_CODEC,
                (payload, context) -> TotalDebug.get().serverScripts().stopScript(
                        (ServerPlayer) context.player(),
                        payload.scriptId()
                )
        );
    }
}
