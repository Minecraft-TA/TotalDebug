package com.github.minecraft_ta.totaldebug.client.resource;

import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Path;
import com.github.minecraft_ta.totaldebug.client.TotalDebugClient;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetPacksPayload;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Reloads what Companion's edited resources need: the language, every client resource, or the singleplayer server's
 * data, as {@code /reload} does. The managed pack is enabled at the top of each stack first, so its files win; only
 * packs fixed at the top stay above it.
 */
public final class ResourceReloads {
    /** A resource reload that has not finished, and the folder packs selected when it started; client thread only. */
    private static CompletableFuture<Void> pendingResources;
    private static Set<String> pendingPacks = Set.of();

    private ResourceReloads() {
    }

    /** Runs {@code request} and answers once every reload finished. Client thread only. */
    public static void reload(ReloadPayload request, Consumer<ReloadResultPayload> answer) {
        long started = System.nanoTime();
        ReloadProblems problems = ReloadProblems.open(request.watched());
        // Data and resources reload independently, so one failing does not keep the other from the game.
        List<CompletableFuture<Void>> reloads = new ArrayList<>();
        if (request.kinds().contains(ReloadPayload.Kind.DATA)) reloads.add(attempt(() -> reloadData(request.managedDataPack())));
        Set<ReloadPayload.Kind> kinds = request.kinds();
        if (kinds.contains(ReloadPayload.Kind.RESOURCES) || kinds.contains(ReloadPayload.Kind.LANGUAGE)
                || kinds.contains(ReloadPayload.Kind.TEXTURES)) {
            reloads.add(attempt(() -> reloadResources(request.managedResourcePack(), kinds, request.watched())));
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

    /**
     * Enables exactly the packs {@code request} names, lowest first, as the game's pack screen or {@code /datapack} does,
     * then answers once the reload that needs is done. Packs the game requires are added where it puts them; a pack it
     * does not know, or a datapack that requests features the world does not have, fails the request unchanged. Client
     * thread only.
     */
    public static void select(SetPacksPayload request, Consumer<ReloadResultPayload> answer) {
        long started = System.nanoTime();
        attempt(() -> request.side() == SetPacksPayload.Side.RESOURCES ? selectResources(request.enabled())
                : selectData(request.world(), request.enabled())).whenComplete((ignored, failure) -> answer.accept(new ReloadResultPayload(
                request.requestId(), (System.nanoTime() - started) / 1_000_000, List.of(), failure == null ? "" : message(failure))));
    }

    private static CompletableFuture<Void> selectResources(List<String> enabled) {
        Minecraft minecraft = Minecraft.getInstance();
        PackRepository packs = minecraft.getResourcePackRepository();
        packs.reload();
        List<String> unknown = enabled.stream().filter(id -> packs.getPack(id) == null).toList();
        if (!unknown.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("The game has no resource pack " + String.join(", ", unknown)));
        }
        List<String> before = List.copyOf(packs.getSelectedIds());
        packs.setSelected(enabled);
        saveOptions(minecraft);
        if (List.copyOf(packs.getSelectedIds()).equals(before)) return CompletableFuture.completedFuture(null);
        return reloadAll(minecraft);
    }

    private static CompletableFuture<Void> selectData(String world, List<String> enabled) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No singleplayer world is open"));
        }
        // Chosen for the world Companion saw the game play; the game may have gone to another since.
        Path played = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if (!played.equals(Path.of(world).toAbsolutePath().normalize())) {
            return CompletableFuture.failedFuture(new IllegalStateException("The game no longer plays the world "
                    + Path.of(world).getFileName() + "; nothing was changed"));
        }
        return CompletableFuture.supplyAsync(() -> {
            PackRepository packs = server.getPackRepository();
            packs.reload();
            List<String> refused = new ArrayList<>();
            for (String id : enabled) {
                Pack pack = packs.getPack(id);
                if (pack == null) refused.add(id + " is not a datapack of the world");
                else if (!pack.getRequestedFeatures().isSubsetOf(server.getWorldData().enabledFeatures())) {
                    refused.add(id + " needs features the world does not have");
                }
            }
            if (!refused.isEmpty()) throw new IllegalArgumentException(String.join("; ", refused));
            return server.reloadResources(enabled);
        }, server).thenCompose(reload -> reload);
    }

    /** Saves the selected resource packs into {@code options.txt}, as {@code Options.updateResourcePacks} does. */
    private static void saveOptions(Minecraft minecraft) {
        minecraft.options.resourcePacks.clear();
        minecraft.options.incompatibleResourcePacks.clear();
        for (Pack pack : minecraft.getResourcePackRepository().getSelectedPacks()) {
            if (pack.isFixedPosition() || pack.isHidden()) continue;
            minecraft.options.resourcePacks.add(pack.getId());
            if (!pack.getCompatibility().isCompatible()) minecraft.options.incompatibleResourcePacks.add(pack.getId());
        }
        minecraft.options.save();
    }

    /** Reloads every client resource, failing when the game turns the folder packs off after a failed reload. */
    private static CompletableFuture<Void> reloadAll(Minecraft minecraft) {
        CompletableFuture<Void> reload = new CompletableFuture<>();
        minecraft.reloadResourcePacks().whenComplete((ignored, failure) -> {
            if (failure == null) reload.complete(null);
            else reload.completeExceptionally(failure);
        });
        Set<String> folderPacks = minecraft.getResourcePackRepository().getSelectedIds().stream()
                .filter(id -> id.startsWith("file/")).collect(Collectors.toSet());
        if (!folderPacks.isEmpty()) {
            pendingResources = reload;
            pendingPacks = folderPacks;
        }
        return reload;
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
     * again and never completes the reload it was asked for; the folder packs it had selected, the managed pack or the
     * player's own, are then gone. Client thread only, on ticks without a loading overlay.
     */
    public static void tick() {
        CompletableFuture<Void> pending = pendingResources;
        if (pending == null) return;
        if (pending.isDone()) {
            pendingResources = null;
            return;
        }
        if (Minecraft.getInstance().getResourcePackRepository().getSelectedIds().containsAll(pendingPacks)) return;
        pendingResources = null;
        pending.completeExceptionally(new IllegalStateException(
                "The game could not load the resources and turned off every resource pack; its log names the cause"));
    }

    /**
     * Enables the managed pack, then shows the edits the quick way when only the language or textures' pixels changed:
     * the language reloaded alone, the textures put in place. Otherwise, or where that cannot show them, every resource
     * is reloaded.
     */
    private static CompletableFuture<Void> reloadResources(String managedPack, Set<ReloadPayload.Kind> kinds, List<String> watched) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean enabled = !managedPack.isEmpty() && enableOnTop(minecraft.getResourcePackRepository(), managedPack);
        // As Options.updateResourcePacks saves them, which would also start a reload of its own.
        if (enabled) saveOptions(minecraft);
        if (!kinds.contains(ReloadPayload.Kind.RESOURCES) && !enabled && quickly(managedPack, kinds, watched)) {
            // A full reload tells the catalog through its reload listener; these quick ones do not.
            TotalDebugClient.current().ifPresent(TotalDebugClient::resourcesReloaded);
            return CompletableFuture.completedFuture(null);
        }
        return reloadAll(minecraft);
    }

    /**
     * Shows the watched language files and textures without a full reload, where the running game already reads them from
     * the managed pack; returns false where it cannot. A save into the player's own pack names no pack, and the copy on
     * top may then come from another of theirs, so it takes the full reload.
     */
    private static boolean quickly(String managedPack, Set<ReloadPayload.Kind> kinds, List<String> watched) {
        if (managedPack.isEmpty()) return false;
        List<String> textures = watched.stream().filter(TextureUploads::texture).toList();
        List<String> language = watched.stream().filter(path -> !TextureUploads.texture(path)).toList();
        if (kinds.contains(ReloadPayload.Kind.LANGUAGE) && !suppliedBy(managedPack, language)) return false;
        if (kinds.contains(ReloadPayload.Kind.TEXTURES) && !TextureUploads.upload(textures, managedPack)) return false;
        if (kinds.contains(ReloadPayload.Kind.LANGUAGE)) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.getLanguageManager().onResourceManagerReload(minecraft.getResourceManager());
        }
        return true;
    }

    /**
     * Whether the running resource manager already reads every watched language file from the managed pack. A pack or
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
