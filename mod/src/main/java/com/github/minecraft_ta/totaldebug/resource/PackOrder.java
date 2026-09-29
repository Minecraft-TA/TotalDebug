package com.github.minecraft_ta.totaldebug.resource;

import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.List;
import java.util.function.Predicate;

/** Where the pack Companion manages goes in a stack of resource packs or datapacks: on top of every pack the player orders. */
public final class PackOrder {
    private PackOrder() {
    }

    /**
     * Moves {@code id} above every pack the player orders but below the packs fixed at the top, such as a server's pack.
     * The repository keeps the order it is given, even for a fixed pack, so the order is made here.
     */
    public static void placeOnTop(List<String> selected, String id, Predicate<String> fixedAtTop) {
        selected.remove(id);
        int index = selected.size();
        while (index > 0 && fixedAtTop.test(selected.get(index - 1))) index--;
        selected.add(index, id);
    }

    /** Whether a pack of {@code packs} keeps its place at the top, such as a server's pack; vanilla is fixed at the bottom. */
    public static Predicate<String> fixedAtTop(PackRepository packs) {
        return id -> {
            Pack pack = packs.getPack(id);
            return pack != null && pack.isFixedPosition() && pack.getDefaultPosition() == Pack.Position.TOP;
        };
    }
}
