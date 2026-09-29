package com.github.minecraft_ta.totaldebug.server.world;

import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.DatapacksMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReloadResultMessage;
import com.github.minecraft_ta.totaldebug.resource.PackOrder;
import com.github.minecraft_ta.totaldebug.resource.PackStacks;
import com.github.minecraft_ta.totaldebug.resource.ReloadProblems;
import com.github.minecraft_ta.totaldebug.server.ServerRelay;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The world's datapacks as Companion changes them (see {@code docs/MOD_SIDES.md}): the server reloads its data, as
 * {@code /reload} does, or enables exactly the datapacks Companion names, as {@code /datapack} does, and answers through
 * the relay. It names them too, in place of the game client: when Companion asks, and after each time the data loaded.
 * Only the player whose singleplayer world it is may change or read them so far; who else may on a server is decided with
 * the permissions of a later layer.
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
     * Enables exactly the datapacks {@code request} names, lowest first, and answers once the data reloaded. A pack the
     * world does not have, or one that requests features the world lacks, fails the request unchanged. Server thread.
     */
    public void select(ServerPlayer player, int companion, SetPacksPayload request) {
        long started = System.nanoTime();
        MinecraftServer server = player.server;
        if (refused(player, companion, request.requestId())) return;
        if (request.side() != SetPacksPayload.Side.DATA) {
            answer(player, companion, new ReloadResultPayload(request.requestId(), 0, List.of(),
                    "The server selects the world's datapacks only; the client selects its resource packs"));
            return;
        }
        attempt(() -> {
            PackRepository packs = server.getPackRepository();
            packs.reload();
            List<String> refused = new ArrayList<>();
            for (String id : request.enabled()) {
                Pack pack = packs.getPack(id);
                if (pack == null) refused.add(id + " is not a datapack of the world");
                else if (!pack.getRequestedFeatures().isSubsetOf(server.getWorldData().enabledFeatures())) {
                    refused.add(id + " needs features the world does not have");
                }
            }
            if (!refused.isEmpty()) throw new IllegalArgumentException(String.join("; ", refused));
            return server.reloadResources(request.enabled());
        }).whenComplete((ignored, failure) -> answer(player, companion, new ReloadResultPayload(request.requestId(),
                (System.nanoTime() - started) / 1_000_000, List.of(), failure == null ? "" : message(failure))));
    }

    /**
     * Names the world's datapacks to {@code player}'s Companion connection {@code companion}. Only the owner of a
     * singleplayer world is told so far; to anyone else the world names none, as before. Server thread.
     */
    public void report(ServerPlayer player, int companion) {
        MinecraftServer server = player.server;
        if (!server.isSingleplayerOwner(player.getGameProfile())) return;
        this.relay.send(server, player, companion, new DatapacksMessage(PackStacks.of(server.getPackRepository(),
                SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA), server.getWorldPath(LevelResource.DATAPACK_DIR),
                server.getWorldData().enabledFeatures())));
    }

    /** The world's data loaded again: names the datapacks to those of {@code players} whose Companion reached the server. */
    public void loaded(Stream<ServerPlayer> players) {
        players.forEach(player -> {
            int companion = this.relay.companion(player);
            if (companion != 0) report(player, companion);
        });
    }

    /** Refuses a change of the world's datapacks by anyone but the owner of a singleplayer world, and says so. */
    private boolean refused(ServerPlayer player, int companion, int requestId) {
        if (player.server.isSingleplayerOwner(player.getGameProfile())) return false;
        answer(player, companion, new ReloadResultPayload(requestId, 0, List.of(),
                "Companion changes the datapacks of a singleplayer world for its owner only; a server's are not open to it yet"));
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
