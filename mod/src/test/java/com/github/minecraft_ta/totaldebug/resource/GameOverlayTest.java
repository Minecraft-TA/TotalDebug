package com.github.minecraft_ta.totaldebug.resource;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameOverlayTest {
    private final GameOverlay.Resources resources = new GameOverlay.Resources(new PackLocationInfo(GameOverlay.ID,
            Component.literal("TotalDebug"), PackSource.BUILT_IN, Optional.empty()));

    @AfterEach
    void clear() {
        GameOverlay.clear();
    }

    @Test
    void listsFromAFolderOrFromTheNamespaceRoot() {
        GameOverlay.set("data/testmod/recipe/gear.json", new byte[]{'{', '}'});
        GameOverlay.set("data/testmod/loot_table/blocks/gear.json", new byte[]{'{', '}'});
        GameOverlay.set("data/other/recipe/cog.json", new byte[]{'{', '}'});

        assertEquals(List.of(ResourceLocation.fromNamespaceAndPath("testmod", "recipe/gear.json")), list("recipe"));
        assertEquals(List.of(ResourceLocation.fromNamespaceAndPath("testmod", "loot_table/blocks/gear.json"),
                ResourceLocation.fromNamespaceAndPath("testmod", "recipe/gear.json")), list(""),
                "a listener that scans from the namespace root sees every entry of the namespace");
    }

    private List<ResourceLocation> list(String path) {
        List<ResourceLocation> found = new ArrayList<>();
        this.resources.listResources(PackType.SERVER_DATA, "testmod", path, (location, content) -> found.add(location));
        found.sort(null);
        return found;
    }
}
