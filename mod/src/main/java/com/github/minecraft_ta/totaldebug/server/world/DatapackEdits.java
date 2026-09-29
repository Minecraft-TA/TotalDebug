package com.github.minecraft_ta.totaldebug.server.world;

import com.github.minecraft_ta.totaldebug.change.ChangeTable;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Enables and orders the world's datapacks the way {@code /datapack} does: the selection changes and the server reloads
 * its data, which saves the selection with the world, and the change is answered once it did. The one target is
 * {@value #TARGET}, and its value the enabled packs, lowest first, as a JSON array of their ids as the game keeps them:
 * without the hidden parts of the mods' pack, which {@code PackRepository.getSelectedIds} and {@code level.dat} leave out
 * too. Packs the game requires are added where it puts them. Server thread only.
 */
final class DatapackEdits implements ChangeTable.Category {
    static final String CATEGORY = "datapacks";
    static final String TARGET = "datapacks";

    private final MinecraftServer server;
    /** The selection the data was loaded with before the change being made, read before the folders are read again. */
    private List<String> loaded;
    /** The world's data configuration before the change, which a reload that got as far as taking the selection replaced. */
    private WorldDataConfiguration configuration;

    DatapackEdits(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String read(String target) {
        target(target);
        JsonArray ids = new JsonArray();
        this.server.getPackRepository().getSelectedIds().forEach(ids::add);
        return ids.toString();
    }

    @Override
    public void check(String target, String value) {
        target(target);
        PackRepository packs = this.server.getPackRepository();
        if (this.loaded == null) {
            this.loaded = List.copyOf(packs.getSelectedIds());
            this.configuration = this.server.getWorldData().getDataConfiguration();
        }
        // As /reload does, the world's folder is read again, so a pack put there since is found; one gone is dropped.
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
        this.server.getPackRepository().setSelected(ids(value));
    }

    /**
     * Reloads the data with the new selection, which the server then keeps for the world, unless the data was already
     * loaded with it. Should the reload fail before the server took the selection, it keeps the data it had, so the
     * selection it was loaded with is put back; one that failed after it took it keeps the new one, as the world does.
     */
    @Override
    public CompletableFuture<Void> finish() {
        List<String> loaded = this.loaded;
        WorldDataConfiguration configuration = this.configuration;
        this.loaded = null;
        this.configuration = null;
        PackRepository packs = this.server.getPackRepository();
        if (loaded == null || List.copyOf(packs.getSelectedIds()).equals(loaded)) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> reloaded;
        try {
            reloaded = this.server.reloadResources(packs.getSelectedIds());
        } catch (RuntimeException failure) {
            reloaded = CompletableFuture.failedFuture(failure);
        }
        return reloaded.handleAsync((ignored, failure) -> {
            if (failure == null) return null;
            if (this.server.getWorldData().getDataConfiguration() == configuration) packs.setSelected(loaded);
            throw failure instanceof RuntimeException unchecked ? unchecked : new IllegalStateException(failure);
        }, this.server);
    }

    @Override
    public String name(String target) {
        return "The world's datapacks";
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
