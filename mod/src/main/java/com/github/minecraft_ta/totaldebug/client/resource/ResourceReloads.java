package com.github.minecraft_ta.totaldebug.client.resource;

import com.github.minecraft_ta.totaldebug.client.TotalDebugClient;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.Resource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Reloads what Companion's edited resources need: the language, every client resource, or the singleplayer server's
 * data, as {@code /reload} does. The managed pack is enabled at the top of each stack first, so its files win; only
 * packs fixed at the top stay above it.
 */
public final class ResourceReloads {
    /** A resource reload that has not finished, and the managed pack it selected; client thread only. */
    private static CompletableFuture<Void> pendingResources;
    private static String pendingPack;

    private ResourceReloads() {
    }

    /** Runs {@code request} and answers once every reload finished. Client thread only. */
    public static void reload(ReloadPayload request, Consumer<ReloadResultPayload> answer) {
        long started = System.nanoTime();
        ReloadProblems problems = ReloadProblems.open(request.watched());
        // Data and resources reload independently, so one failing does not keep the other from the game.
        List<CompletableFuture<Void>> reloads = new ArrayList<>();
        if (request.kinds().contains(ReloadPayload.Kind.DATA)) reloads.add(attempt(() -> reloadData(request.managedPack())));
        if (request.kinds().contains(ReloadPayload.Kind.RESOURCES) || request.kinds().contains(ReloadPayload.Kind.LANGUAGE)) {
            boolean languageOnly = !request.kinds().contains(ReloadPayload.Kind.RESOURCES);
            reloads.add(attempt(() -> reloadResources(request.managedPack(), languageOnly, request.watched())));
        }
        CompletableFuture.allOf(reloads.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            List<ReloadResultPayload.Problem> found = problems.problems();
            problems.close();
            long millis = (System.nanoTime() - started) / 1_000_000;
            List<String> errors = new ArrayList<>();
            for (CompletableFuture<Void> reload : reloads) {
                if (reload.isCompletedExceptionally()) errors.add(message(reload.exceptionNow()));
            }
            answer.accept(new ReloadResultPayload(request.requestId(), millis, found, String.join("; ", errors)));
        });
    }

    private static CompletableFuture<Void> attempt(Supplier<CompletableFuture<Void>> reload) {
        try {
            return reload.get();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Fails a resource reload the game rolled back. When a reload fails, Minecraft turns off every resource pack, reloads
     * again and never completes the reload it was asked for; the managed pack it had selected is then gone. Client
     * thread only, on ticks without a loading overlay.
     */
    public static void tick() {
        CompletableFuture<Void> pending = pendingResources;
        if (pending == null) return;
        if (pending.isDone()) {
            pendingResources = null;
            return;
        }
        if (Minecraft.getInstance().getResourcePackRepository().getSelectedIds().contains(pendingPack)) return;
        pendingResources = null;
        pending.completeExceptionally(new IllegalStateException(
                "The game could not load the resources and turned off every resource pack; its log names the cause"));
    }

    /** Enables the managed pack and reloads the language alone when that shows the edit, otherwise every resource. */
    private static CompletableFuture<Void> reloadResources(String managedPack, boolean languageOnly, List<String> watched) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean enabled = !managedPack.isEmpty() && enableOnTop(minecraft.getResourcePackRepository(), managedPack);
        if (enabled) {
            // As Options.updateResourcePacks saves them, which would also start a reload of its own.
            minecraft.options.resourcePacks.clear();
            minecraft.options.incompatibleResourcePacks.clear();
            for (Pack pack : minecraft.getResourcePackRepository().getSelectedPacks()) {
                if (pack.isFixedPosition() || pack.isHidden()) continue;
                minecraft.options.resourcePacks.add(pack.getId());
                if (!pack.getCompatibility().isCompatible()) minecraft.options.incompatibleResourcePacks.add(pack.getId());
            }
            minecraft.options.save();
        }
        if (languageOnly && !enabled && suppliedBy(managedPack, watched)) {
            minecraft.getLanguageManager().onResourceManagerReload(minecraft.getResourceManager());
            // A full reload tells the catalog through its reload listener; this reload of the language alone does not.
            TotalDebugClient.current().ifPresent(TotalDebugClient::resourcesReloaded);
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> reload = new CompletableFuture<>();
        minecraft.reloadResourcePacks().whenComplete((ignored, failure) -> {
            if (failure == null) reload.complete(null);
            else reload.completeExceptionally(failure);
        });
        if (minecraft.getResourcePackRepository().getSelectedIds().contains(managedPack)) {
            pendingResources = reload;
            pendingPack = managedPack;
        }
        return reload;
    }

    /**
     * Whether the running resource manager already reads every watched language file from the managed pack; a pack or
     * namespace added since the last reload is only seen after a full reload.
     */
    private static boolean suppliedBy(String managedPack, List<String> watched) {
        for (String path : watched) {
            String[] parts = path.split("/", 3);
            if (parts.length < 3 || !parts[0].equals("assets")) return false;
            ResourceLocation location = ResourceLocation.tryBuild(parts[1], parts[2]);
            if (location == null) return false;
            List<Resource> stack = Minecraft.getInstance().getResourceManager().getResourceStack(location);
            if (stack.stream().noneMatch(resource -> resource.sourcePackId().equals(managedPack))) return false;
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
            // Writing into the managed pack asks for it, even where the world had disabled it.
            if (!managedPack.isEmpty() && packs.getAvailableIds().contains(managedPack)) {
                placeOnTop(selected, managedPack, fixedAtTop(packs));
            }
            return server.reloadResources(selected);
        }, server).thenCompose(reload -> reload);
    }

    /**
     * Selects {@code id} above every pack the player orders, below packs fixed at the top such as a server's pack;
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
     * Moves {@code id} above every pack the player orders but below the packs fixed at the top, such as a server's pack.
     * The repository keeps the order it is given, even for a fixed pack, so the order is made here.
     */
    static void placeOnTop(List<String> selected, String id, Predicate<String> fixedAtTop) {
        selected.remove(id);
        int index = selected.size();
        while (index > 0 && fixedAtTop.test(selected.get(index - 1))) index--;
        selected.add(index, id);
    }

    /** Whether a pack of {@code packs} keeps its place at the top, such as a server's pack; vanilla is fixed at the bottom. */
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
