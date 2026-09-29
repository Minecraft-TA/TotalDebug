package com.github.minecraft_ta.totaldebug.server.world;

import com.github.minecraft_ta.totaldebug.change.ChangeTable;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Enables and orders the world's datapacks the way {@code /datapack} does: the selection changes and the server reloads
 * its data, which saves the selection with the world, and the change is answered once it did. The one target is
 * {@value #TARGET}, and its value the enabled packs, lowest first, as a JSON array of their ids, without the parts of the
 * mods' pack, {@code mod/<ids>}, which come and go with it; a datapack a mod adds, {@code mod/<modid>:<path>}, is a pack
 * like any other. Packs the game requires are added where it puts them. Server thread only.
 */
final class DatapackEdits implements ChangeTable.Category {
    static final String CATEGORY = "datapacks";
    static final String TARGET = "datapacks";

    private final MinecraftServer server;
    /** The selection before the change being made, put back when its reload fails; null while it selected nothing new. */
    private List<String> before;

    DatapackEdits(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String read(String target) {
        target(target);
        JsonArray ids = new JsonArray();
        for (String id : this.server.getPackRepository().getSelectedIds()) {
            if (!partOfTheModsPack(id)) ids.add(id);
        }
        return ids.toString();
    }

    @Override
    public void check(String target, String value) {
        target(target);
        PackRepository packs = this.server.getPackRepository();
        // As /reload does, the world's folder is read again, so a pack put there since is found.
        packs.reload();
        List<String> refused = new ArrayList<>();
        for (String id : ids(value)) {
            Pack pack = packs.getPack(id);
            if (pack == null) refused.add(id + " is not a datapack of the world");
            else if (!pack.getRequestedFeatures().isSubsetOf(this.server.getWorldData().enabledFeatures())) {
                refused.add(id + " needs features the world does not have");
            }
        }
        if (!refused.isEmpty()) throw new IllegalArgumentException(String.join("; ", refused));
    }

    @Override
    public void set(String target, String value) {
        PackRepository packs = this.server.getPackRepository();
        List<String> before = List.copyOf(packs.getSelectedIds());
        packs.setSelected(ids(value));
        if (this.before == null && !List.copyOf(packs.getSelectedIds()).equals(before)) this.before = before;
    }

    /**
     * Reloads the data with the new selection, which the server then keeps for the world. Should the reload fail, the
     * server keeps the data it had, so the selection before is put back.
     */
    @Override
    public CompletableFuture<Void> finish() {
        List<String> before = this.before;
        this.before = null;
        if (before == null) return CompletableFuture.completedFuture(null);
        PackRepository packs = this.server.getPackRepository();
        CompletableFuture<Void> reloaded;
        try {
            reloaded = this.server.reloadResources(packs.getSelectedIds());
        } catch (RuntimeException failure) {
            reloaded = CompletableFuture.failedFuture(failure);
        }
        return reloaded.handleAsync((ignored, failure) -> {
            if (failure == null) return null;
            packs.setSelected(before);
            throw failure instanceof RuntimeException unchecked ? unchecked : new IllegalStateException(failure);
        }, this.server);
    }

    @Override
    public String name(String target) {
        return "The world's datapacks";
    }

    /** A part of the mods' pack, {@code mod/<ids>}, rather than a datapack a mod adds, {@code mod/<modid>:<path>}. */
    static boolean partOfTheModsPack(String id) {
        return id.startsWith("mod/") && !id.contains(":");
    }

    private static void target(String target) {
        if (!target.equals(TARGET)) throw new IllegalArgumentException(target + " is not a pack selection of the world's server");
    }

    private static List<String> ids(String value) {
        try {
            List<String> ids = new ArrayList<>();
            for (JsonElement id : JsonParser.parseString(value).getAsJsonArray()) ids.add(id.getAsString());
            return ids;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("A pack selection is a JSON array of pack ids: " + invalid.getMessage());
        }
    }
}
