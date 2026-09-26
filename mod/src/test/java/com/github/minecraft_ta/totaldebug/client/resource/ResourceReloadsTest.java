package com.github.minecraft_ta.totaldebug.client.resource;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceReloadsTest {
    @Test
    void theManagedPackGoesBelowThePacksFixedAtTheTop() {
        Set<String> fixed = Set.of("totaldebug_game");
        List<String> selected = new ArrayList<>(List.of("vanilla", "file/TotalDebug", "file/Faithful", "totaldebug_game"));

        ResourceReloads.placeOnTop(selected, "file/TotalDebug", fixed::contains);
        assertEquals(List.of("vanilla", "file/Faithful", "file/TotalDebug", "totaldebug_game"), selected,
                "the in-memory pack stays above, so a try still wins over the saved copy");

        ResourceReloads.placeOnTop(selected, "file/TotalDebug", fixed::contains);
        assertEquals(List.of("vanilla", "file/Faithful", "file/TotalDebug", "totaldebug_game"), selected, "placing twice changes nothing");

        List<String> onlyFixed = new ArrayList<>(List.of("vanilla", "totaldebug_game"));
        ResourceReloads.placeOnTop(onlyFixed, "file/TotalDebug", fixed::contains);
        assertEquals(List.of("vanilla", "file/TotalDebug", "totaldebug_game"), onlyFixed, "vanilla, fixed at the bottom, stays below");
    }
}
