package com.github.minecraft_ta.totaldebug.client.resource;

import com.github.minecraft_ta.totaldebug.change.ChangeTable;
import com.github.minecraft_ta.totaldebug.client.TotalDebugClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Enables and orders the resource packs the way the pack screen does: the selection changes, {@code options.txt} is saved
 * and the client's resources reload, and the change is answered once they did. The one target is {@value #TARGET}, and
 * its value the enabled packs, lowest first, as a JSON array of their ids, as {@code options.txt} keeps them: without the
 * parts of another pack or the packs fixed in place. Packs the game requires are added where it puts them. Client thread
 * only.
 */
public final class ResourcePackEdits implements ChangeTable.Category {
    public static final String CATEGORY = "resourcePacks";
    public static final String TARGET = "resourcePacks";

    /** Whether the change being made selected other packs than before, which then need a reload. */
    private boolean selected;

    @Override
    public String read(String target) {
        target(target);
        JsonArray ids = new JsonArray();
        for (Pack pack : Minecraft.getInstance().getResourcePackRepository().getSelectedPacks()) {
            if (!pack.isHidden() && !pack.isFixedPosition()) ids.add(pack.getId());
        }
        return ids.toString();
    }

    @Override
    public void check(String target, String value) {
        target(target);
        PackRepository packs = Minecraft.getInstance().getResourcePackRepository();
        // As the pack screen does, the folders are read again, so a pack put there since is found.
        packs.reload();
        List<String> unknown = ids(value).stream().filter(id -> packs.getPack(id) == null).toList();
        if (!unknown.isEmpty()) throw new IllegalArgumentException("The game has no resource pack " + String.join(", ", unknown));
    }

    @Override
    public void set(String target, String value) {
        PackRepository packs = Minecraft.getInstance().getResourcePackRepository();
        List<String> before = List.copyOf(packs.getSelectedIds());
        packs.setSelected(ids(value));
        this.selected |= !List.copyOf(packs.getSelectedIds()).equals(before);
    }

    /** Saves the selection and reloads the resources it changed; the packs found in the folders are told either way. */
    @Override
    public CompletableFuture<Void> finish() {
        Minecraft minecraft = Minecraft.getInstance();
        save(minecraft);
        ResourceReloads.selected();
        boolean reload = this.selected;
        this.selected = false;
        if (reload) return ResourceReloads.reloadAll(minecraft);
        TotalDebugClient.current().ifPresent(TotalDebugClient::packsChanged);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public String name(String target) {
        return "The resource packs";
    }

    /** Saves the selected resource packs into {@code options.txt}, as {@code Options.updateResourcePacks} does. */
    static void save(Minecraft minecraft) {
        minecraft.options.resourcePacks.clear();
        minecraft.options.incompatibleResourcePacks.clear();
        for (Pack pack : minecraft.getResourcePackRepository().getSelectedPacks()) {
            if (pack.isFixedPosition() || pack.isHidden()) continue;
            minecraft.options.resourcePacks.add(pack.getId());
            if (!pack.getCompatibility().isCompatible()) minecraft.options.incompatibleResourcePacks.add(pack.getId());
        }
        minecraft.options.save();
    }

    private static void target(String target) {
        if (!target.equals(TARGET)) throw new IllegalArgumentException(target + " is not a pack selection of the game client");
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
