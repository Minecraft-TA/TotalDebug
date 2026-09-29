package com.github.minecraft_ta.totaldebug.server;

import com.github.minecraft_ta.totaldebug.protocol.scnet.DatapacksRequestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReloadMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.SetPacksMessage;
import com.github.minecraft_ta.totaldebug.server.world.WorldDatapacks;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.protocol.scnet.CompanionLeftMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerScriptsRequestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.StopScriptMessage;
import com.github.minecraft_ta.totaldebug.server.script.ServerScriptService;
import com.github.tth05.scnet.message.AbstractMessage;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Objects;

/**
 * What the server does with each message Companion sends it through the relay (see {@code docs/MOD_SIDES.md}). A new
 * server operation adds its message here and in {@code RelayedMessages}.
 */
public final class ServerOperations {
    /** One server operation, for {@code player}'s Companion connection {@code companion}. */
    private interface Operation {
        void run(ServerPlayer player, int companion, AbstractMessage message);
    }

    private final Map<Class<? extends AbstractMessage>, Operation> operations;

    public ServerOperations(ServerScriptService scripts, WorldDatapacks datapacks) {
        Objects.requireNonNull(scripts, "scripts");
        Objects.requireNonNull(datapacks, "datapacks");
        this.operations = Map.of(
                ServerScriptsRequestMessage.class, (player, companion, message) ->
                        scripts.sendAccess(player, companion, ((ServerScriptsRequestMessage) message).request()),
                RunScriptMessage.class, (player, companion, message) -> scripts.runScript(player, companion, (RunScriptMessage) message),
                StopScriptMessage.class, (player, companion, message) ->
                        scripts.stopScript(player, companion, ((StopScriptMessage) message).scriptId()),
                CompanionLeftMessage.class, (player, companion, message) -> scripts.companionLeft(player, companion),
                ReloadMessage.class, (player, companion, message) -> datapacks.reload(player, companion, ((ReloadMessage) message).payload()),
                SetPacksMessage.class, (player, companion, message) -> datapacks.select(player, companion, ((SetPacksMessage) message).payload()),
                DatapacksRequestMessage.class, (player, companion, message) -> datapacks.report(player, companion));
    }

    /** Runs {@code message}'s operation for {@code player}'s Companion connection {@code companion}. Server thread. */
    void handle(ServerPlayer player, int companion, AbstractMessage message) {
        Operation operation = this.operations.get(message.getClass());
        if (operation == null) {
            TotalDebug.LOGGER.warn("The server has no operation for {}", message.getClass().getSimpleName());
            return;
        }
        operation.run(player, companion, message);
    }
}
