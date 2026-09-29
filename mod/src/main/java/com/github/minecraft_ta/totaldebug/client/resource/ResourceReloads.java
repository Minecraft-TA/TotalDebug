package com.github.minecraft_ta.totaldebug.client.resource;

import com.github.minecraft_ta.totaldebug.client.TotalDebugClient;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.resource.PackOrder;
import com.github.minecraft_ta.totaldebug.resource.ReloadProblems;
import net.minecraft.client.Minecraft;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Reloads what Companion's edited resources need in the game client: the language, or every client resource. The
 * managed pack is enabled at the top of the resource packs first, so its files win; only packs fixed at the top stay
 * above it. The world's data is reloaded by its server ({@code WorldDatapacks}).
 */
public final class ResourceReloads {
    /** A resource reload that has not finished, and the folder packs selected when it started. */
    private record Pending(CompletableFuture<Void> reload, Set<String> packs) {
    }

    /** The resource reloads that have not finished, as a pack change and an edit's reload can overlap; client thread only. */
    private static final List<Pending> pending = new ArrayList<>();

    private ResourceReloads() {
    }

    /** Runs {@code request} and answers once every reload finished. Client thread only. */
    public static void reload(ReloadPayload request, Consumer<ReloadResultPayload> answer) {
        long started = System.nanoTime();
        if (request.kinds().contains(ReloadPayload.Kind.DATA)) {
            answer.accept(new ReloadResultPayload(request.requestId(), 0, List.of(), "The world's data is reloaded by its server"));
            return;
        }
        ReloadProblems problems = ReloadProblems.open(request.watched());
        attempt(() -> reloadResources(request.managedResourcePack(), request.kinds(), request.watched())).whenComplete((ignored, failure) -> {
            List<ReloadResultPayload.Problem> found = problems.problems();
            problems.close();
            answer.accept(new ReloadResultPayload(request.requestId(), (System.nanoTime() - started) / 1_000_000, found,
                    failure == null ? "" : message(failure)));
        });
    }

    /** Reloads every client resource, failing when the game turns the folder packs off after a failed reload. */
    static CompletableFuture<Void> reloadAll(Minecraft minecraft) {
        CompletableFuture<Void> reload = new CompletableFuture<>();
        minecraft.reloadResourcePacks().whenComplete((ignored, failure) -> {
            if (failure == null) reload.complete(null);
            else reload.completeExceptionally(failure);
        });
        Set<String> folderPacks = minecraft.getResourcePackRepository().getSelectedIds().stream()
                .filter(id -> id.startsWith("file/")).collect(Collectors.toSet());
        if (!folderPacks.isEmpty()) pending.add(new Pending(reload, folderPacks));
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
     * Fails a resource reload the game rolled back, as the next reload applies. When a reload fails, Minecraft turns off
     * every resource pack, reloads again and never completes the reload it was asked for; the folder packs it had
     * selected, the managed pack or the player's own, are then gone. Client thread only, from the reload listener.
     */
    public static void reloaded() {
        pending.removeIf(reload -> reload.reload().isDone());
        Collection<String> selected = Minecraft.getInstance().getResourcePackRepository().getSelectedIds();
        List<Pending> rolledBack = pending.stream().filter(reload -> !selected.containsAll(reload.packs())).toList();
        pending.removeAll(rolledBack);
        rolledBack.forEach(reload -> reload.reload().completeExceptionally(new IllegalStateException(
                "The game could not load the resources and turned off every resource pack; its log names the cause")));
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
        if (enabled) ResourcePackEdits.save(minecraft);
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
        PackOrder.placeOnTop(selected, id, PackOrder.fixedAtTop(packs));
        packs.setSelected(selected);
        return true;
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && (cause.getMessage() == null || cause instanceof CompletionException)) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
