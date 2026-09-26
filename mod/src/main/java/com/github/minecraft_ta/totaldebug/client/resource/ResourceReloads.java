package com.github.minecraft_ta.totaldebug.client.resource;

import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.resource.GameOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.Resource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Reloads what Companion's edited resources need: the language, every client resource, or the singleplayer server's
 * data, as {@code /reload} does. The managed pack is enabled at the top of each stack first, so its files win; only
 * the game's in-memory pack ({@link GameOverlay}) stays above it.
 */
public final class ResourceReloads {
    private ResourceReloads() {
    }

    /** Runs {@code request} and answers once every reload finished. Client thread only. */
    public static void reload(ReloadPayload request, Consumer<ReloadResultPayload> answer) {
        long started = System.nanoTime();
        ReloadProblems problems = ReloadProblems.open(request.watched());
        CompletableFuture<Void> done;
        try {
            done = CompletableFuture.completedFuture(null);
            if (request.kinds().contains(ReloadPayload.Kind.DATA)) done = done.thenCompose(ignored -> reloadData(request.managedPack()));
            if (request.kinds().contains(ReloadPayload.Kind.RESOURCES) || request.kinds().contains(ReloadPayload.Kind.LANGUAGE)) {
                boolean languageOnly = !request.kinds().contains(ReloadPayload.Kind.RESOURCES);
                done = done.thenComposeAsync(ignored -> reloadResources(request.managedPack(), languageOnly, request.watched()),
                        Minecraft.getInstance());
            }
        } catch (RuntimeException failure) {
            done = CompletableFuture.failedFuture(failure);
        }
        done.whenComplete((ignored, failure) -> {
            List<String> found = problems.problems();
            problems.close();
            long millis = (System.nanoTime() - started) / 1_000_000;
            answer.accept(new ReloadResultPayload(request.requestId(), millis, found,
                    failure == null ? "" : message(failure)));
        });
    }

    /** Reloads what showed the resources Companion tried in the game, once they are gone. Client thread only. */
    public static void reloadAfterClearing(Set<String> paths) {
        boolean assets = paths.stream().anyMatch(path -> path.startsWith("assets/"));
        boolean data = paths.stream().anyMatch(path -> path.startsWith("data/"));
        if (data && Minecraft.getInstance().getSingleplayerServer() != null) reloadData("");
        if (assets) Minecraft.getInstance().reloadResourcePacks();
    }

    /** Enables the managed pack and reloads the language alone when that shows the edit, otherwise every resource. */
    private static CompletableFuture<Void> reloadResources(String managedPack, boolean languageOnly, List<String> watched) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean enabled = !managedPack.isEmpty() && enableOnTop(minecraft.getResourcePackRepository(), managedPack);
        if (enabled) {
            minecraft.options.resourcePacks.clear();
            minecraft.options.incompatibleResourcePacks.clear();
            for (Pack pack : minecraft.getResourcePackRepository().getSelectedPacks()) {
                if (pack.isFixedPosition()) continue;
                minecraft.options.resourcePacks.add(pack.getId());
                if (!pack.getCompatibility().isCompatible()) minecraft.options.incompatibleResourcePacks.add(pack.getId());
            }
            minecraft.options.save();
        }
        if (languageOnly && !enabled && suppliedBy(managedPack, watched)) {
            minecraft.getLanguageManager().onResourceManagerReload(minecraft.getResourceManager());
            return CompletableFuture.completedFuture(null);
        }
        return minecraft.reloadResourcePacks();
    }

    /**
     * Whether the running resource manager already reads every watched language file from the managed pack or the
     * in-memory pack; a pack or namespace added since the last reload is only seen after a full reload.
     */
    private static boolean suppliedBy(String managedPack, List<String> watched) {
        for (String path : watched) {
            String[] parts = path.split("/", 3);
            if (parts.length < 3 || !parts[0].equals("assets")) return false;
            ResourceLocation location = ResourceLocation.tryBuild(parts[1], parts[2]);
            if (location == null) return false;
            List<Resource> stack = Minecraft.getInstance().getResourceManager().getResourceStack(location);
            if (stack.stream().noneMatch(resource -> resource.sourcePackId().equals(managedPack)
                    || resource.sourcePackId().equals(GameOverlay.ID))) return false;
        }
        return true;
    }

    /** Reloads the singleplayer server's data with every pack the world does not disable, as {@code /reload} does. */
    private static CompletableFuture<Void> reloadData(String managedPack) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Data is reloaded only in a singleplayer world, and none is open"));
        }
        return CompletableFuture.supplyAsync(() -> {
            PackRepository packs = server.getPackRepository();
            packs.reload();
            Collection<String> disabled = server.getWorldData().getDataConfiguration().dataPacks().getDisabled();
            List<String> selected = new ArrayList<>(packs.getSelectedIds());
            for (String id : packs.getAvailableIds()) {
                if (!disabled.contains(id) && !selected.contains(id)) selected.add(id);
            }
            if (!managedPack.isEmpty() && selected.contains(managedPack)) placeOnTop(selected, managedPack, fixedAtTop(packs));
            return server.reloadResources(selected);
        }, server).thenCompose(reload -> reload);
    }

    /**
     * Selects {@code id} above every pack the player orders, below packs fixed at the top such as the in-memory pack;
     * returns whether the selection changed.
     */
    private static boolean enableOnTop(PackRepository packs, String id) {
        packs.reload();
        if (!packs.getAvailableIds().contains(id)) return false;
        List<String> ordered = packs.getSelectedPacks().stream().filter(pack -> !pack.isFixedPosition()).map(Pack::getId).toList();
        if (!ordered.isEmpty() && ordered.getLast().equals(id)) return false;
        List<String> selected = new ArrayList<>(packs.getSelectedIds());
        placeOnTop(selected, id, fixedAtTop(packs));
        packs.setSelected(selected);
        return true;
    }

    /**
     * Moves {@code id} above every pack the player orders but below the packs fixed at the top, such as the in-memory
     * pack. The repository keeps the order it is given, even for a fixed pack, so the order is made here.
     */
    static void placeOnTop(List<String> selected, String id, Predicate<String> fixedAtTop) {
        selected.remove(id);
        int index = selected.size();
        while (index > 0 && fixedAtTop.test(selected.get(index - 1))) index--;
        selected.add(index, id);
    }

    /** Whether a pack of {@code packs} keeps its place at the top, such as the in-memory pack; vanilla is fixed at the bottom. */
    private static Predicate<String> fixedAtTop(PackRepository packs) {
        return id -> {
            Pack pack = packs.getPack(id);
            return pack != null && pack.isFixedPosition() && pack.getDefaultPosition() == Pack.Position.TOP;
        };
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && (cause.getMessage() == null || cause instanceof CompletionException)) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
