package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.resource.ArchiveEntrySource;
import com.github.minecraft_ta.totalDebugCompanion.resource.LocalFileSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePathsTest {
    @TempDir Path directory;

    @Test
    void findsThePackPathOfArchiveEntriesAndPackFiles() throws Exception {
        assertEquals(Optional.of("assets/testmod/models/block/gear.json"), ResourcePaths.of(
                new ArchiveEntrySource(this.directory.resolve("testmod.jar"), "assets/testmod/models/block/gear.json", 0)));
        assertEquals(Optional.empty(), ResourcePaths.of(
                new ArchiveEntrySource(this.directory.resolve("testmod.jar"), "META-INF/neoforge.mods.toml", 0)));

        Path pack = this.directory.resolve("resourcepacks/Faithful");
        Path file = pack.resolve("assets/testmod/lang/en_us.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}");
        assertEquals(Optional.empty(), ResourcePaths.of(new LocalFileSource(file)), "a folder without pack.mcmeta is no pack");
        Files.writeString(pack.resolve("pack.mcmeta"), "{}");
        assertEquals(Optional.of("assets/testmod/lang/en_us.json"), ResourcePaths.of(new LocalFileSource(file)));
    }

    @Test
    void namesWhatTheGameReloads() {
        assertEquals(ResourcePaths.Apply.LANGUAGE, ResourcePaths.apply("assets/testmod/lang/en_us.json"));
        assertEquals(ResourcePaths.Apply.RESOURCES, ResourcePaths.apply("assets/testmod/models/block/gear.json"));
        assertEquals(ResourcePaths.Apply.DATA, ResourcePaths.apply("data/testmod/recipe/gear.json"));
        assertEquals(ResourcePaths.Apply.DATA, ResourcePaths.apply("data/minecraft/tags/block/mineable/pickaxe.json"));
        assertEquals(ResourcePaths.Apply.WORLD_LOAD, ResourcePaths.apply("data/testmod/worldgen/biome/glade.json"));
        assertEquals(ResourcePaths.Apply.WORLD_LOAD, ResourcePaths.apply("data/testmod/enchantment/sharp.json"));
    }

    @Test
    void checksJsonTheWayTheGameReadsIt() {
        assertEquals(Optional.empty(), ResourcePaths.check("assets/testmod/models/block/gear.json", "{\"parent\":\"block/cube\"}"));
        assertTrue(ResourcePaths.check("assets/testmod/models/block/gear.json", "{\"parent\":").orElseThrow().startsWith("Not valid JSON"));
        assertEquals(Optional.of("The translation of item.testmod.gear is not a string"),
                ResourcePaths.check("assets/testmod/lang/en_us.json", "{\"item.testmod.gear\":{\"a\":1}}"));
        assertEquals(Optional.empty(), ResourcePaths.check("data/testmod/function/tick.mcfunction", "say {"));
    }
}
