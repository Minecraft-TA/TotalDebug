package com.github.minecraft_ta.totaldebug.server.world;

import com.github.minecraft_ta.totaldebug.change.ChangeTable;
import com.github.minecraft_ta.totaldebug.protocol.message.ChangePayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ChangeResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ChangeResultMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.DatapacksMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReloadResultMessage;
import com.github.minecraft_ta.totaldebug.resource.PackOrder;
import com.github.minecraft_ta.totaldebug.resource.PackStacks;
import com.github.minecraft_ta.totaldebug.resource.ReloadProblems;
import com.github.minecraft_ta.totaldebug.server.ServerPolicy;
import com.github.minecraft_ta.totaldebug.server.ServerRelay;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The world's datapacks as Companion changes them (see {@code docs/MOD_SIDES.md}): the server reloads its data, as
 * {@code /reload} does, or makes a change of its datapacks, as {@code /datapack} does ({@link DatapackEdits}), and
 * answers through the relay. It names them too, in place of the game client: when Companion asks, and after each time the data loaded.
 * The owner of a singleplayer world may always change and read them; anyone else as the server's configuration allows
 * ({@link ServerPolicy#worldChanges}), by default an operator.
 */
public final class WorldDatapacks {
    private final ServerRelay relay;

    public WorldDatapacks(ServerRelay relay) {
        this.relay = Objects.requireNonNull(relay, "relay");
    }

    /**
     * Reloads the data with every pack the world does not disable, the pack Companion manages on top when an edit went
     * into it, and answers with the problems logged about the watched files. Server thread.
     */
    public void reload(ServerPlayer player, int companion, ReloadPayload request) {
        long started = System.nanoTime();
        MinecraftServer server = player.server;
        if (refused(player, companion, request.requestId())) return;
        if (!request.kinds().equals(Set.of(ReloadPayload.Kind.DATA))) {
            answer(player, companion, new ReloadResultPayload(request.requestId(), 0, List.of(),
                    "The server reloads the world's data only; the client reloads its resources"));
            return;
        }
        ReloadProblems problems = ReloadProblems.open(request.watched());
        attempt(() -> {
            PackRepository packs = server.getPackRepository();
            packs.reload();
            Collection<String> disabled = server.getWorldData().getDataConfiguration().dataPacks().getDisabled();
            List<String> selected = new ArrayList<>(packs.getSelectedIds());
            for (String id : packs.getAvailableIds()) {
                if (!disabled.contains(id) && !selected.contains(id)) selected.add(id);
            }
            // Writing into the managed pack asks for it, even where the world had disabled it.
            String managed = request.managedDataPack();
            if (!managed.isEmpty() && packs.getAvailableIds().contains(managed)) {
                PackOrder.placeOnTop(selected, managed, PackOrder.fixedAtTop(packs));
            }
            return server.reloadResources(selected);
        }).whenComplete((ignored, failure) -> {
            List<ReloadResultPayload.Problem> found = problems.problems();
            problems.close();
            answer(player, companion, new ReloadResultPayload(request.requestId(), (System.nanoTime() - started) / 1_000_000,
                    found, failure == null ? "" : message(failure)));
        });
    }

    /**
     * Makes a change of the world's datapacks through the server's change table, and answers once the data reloaded with
     * it. Server thread.
     */
    public void change(ServerPlayer player, int companion, ChangePayload change) {
        MinecraftServer server = player.server;
        ServerPolicy.Decision decision = decision(player);
        if (!decision.allowed()) {
            this.relay.send(server, player, companion, new ChangeResultMessage(ChangeResultPayload.refused(change.requestId(),
                    decision.rejectionReason())));
            return;
        }
        new ChangeTable(Map.of(DatapackEdits.CATEGORY, new DatapackEdits(server)), server).apply(change)
                .thenAccept(result -> this.relay.send(server, player, companion, new ChangeResultMessage(result)));
    }

    /**
     * Names the world's datapacks to {@code player}'s Companion connection {@code companion}, or why the player may not
     * change them, which leaves them unnamed as {@code /datapack list} does. The owner of a singleplayer world is told the
     * world by its folder, as the game client names it; anyone else plays it as a server, whose world the game client
     * names by the address it joined, which the server does not know, so the world goes unnamed. Server thread.
     */
    public void report(ServerPlayer player, int companion) {
        MinecraftServer server = player.server;
        String world = server.isSingleplayerOwner(player.getGameProfile())
                ? PlayingPayload.Singleplayer.of(server.getWorldPath(LevelResource.ROOT)).identity() : "";
        int format = SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA);
        ServerPolicy.Decision decision = decision(player);
        if (!decision.allowed()) {
            this.relay.send(server, player, companion, DatapacksMessage.refused(world, format, decision.rejectionReason()));
            return;
        }
        PackStackPayload packs = PackStacks.of(server.getPackRepository(), format, server.getWorldPath(LevelResource.DATAPACK_DIR),
                server.getWorldData().enabledFeatures());
        // The files are the server's, which a player on another machine cannot read, and whose paths are not theirs to see.
        this.relay.send(server, player, companion, new DatapacksMessage(world, world.isEmpty() ? PackStacks.withoutSources(packs) : packs));
    }

    /**
     * {@code player}'s permission changes, such as when they are made an operator: names the datapacks again, or why they
     * may not change them, once the new level holds, which is after the event. Server thread.
     */
    public void permissionChanged(ServerPlayer player) {
        MinecraftServer server = player.server;
        server.tell(new TickTask(server.getTickCount(), () -> {
            int companion = this.relay.companion(player);
            if (companion != 0 && !player.hasDisconnected()) report(player, companion);
        }));
    }

    /** The world's data loaded again: names the datapacks to those of {@code players} whose Companion reached the server. */
    public void loaded(Stream<ServerPlayer> players) {
        players.forEach(player -> {
            int companion = this.relay.companion(player);
            if (companion != 0) report(player, companion);
        });
    }

    /**
     * Whether {@code player} may change the world: always the owner of a singleplayer world, open to LAN or not; anyone
     * else as the server's configuration allows.
     */
    private static ServerPolicy.Decision decision(ServerPlayer player) {
        MinecraftServer server = player.server;
        boolean owner = server.isSingleplayerOwner(player.getGameProfile());
        // The level /datapack and /reload require, whatever level the server gives new operators.
        return decision(owner, player.hasPermissions(Commands.LEVEL_GAMEMASTERS), owner ? null : ServerPolicy.worldChanges());
    }

    /** Whether the owner of a singleplayer world, or another player with or without operator permission, may change it. */
    static ServerPolicy.Decision decision(boolean owner, boolean operator, ServerPolicy policy) {
        return owner ? ServerPolicy.Decision.accepted() : policy.evaluate(operator);
    }

    /** Refuses a data reload by a player who may not change the world, and says why. */
    private boolean refused(ServerPlayer player, int companion, int requestId) {
        ServerPolicy.Decision decision = decision(player);
        if (decision.allowed()) return false;
        answer(player, companion, new ReloadResultPayload(requestId, 0, List.of(), decision.rejectionReason()));
        return true;
    }

    private void answer(ServerPlayer player, int companion, ReloadResultPayload result) {
        this.relay.send(player.server, player, companion, new ReloadResultMessage(result));
    }

    private static CompletableFuture<Void> attempt(Supplier<CompletableFuture<Void>> reload) {
        try {
            return reload.get();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && (cause.getMessage() == null || cause instanceof CompletionException)) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
