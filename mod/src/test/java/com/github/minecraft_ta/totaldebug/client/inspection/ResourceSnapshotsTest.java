package com.github.minecraft_ta.totaldebug.client.inspection;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ResourceSnapshotsTest {
    private static final byte[] FLUIDS = "{}".getBytes(StandardCharsets.UTF_8);

    @TempDir Path directory;

    @Test
    void aCaptureThatHoldsTheSameKeepsTheArchiveCompanionHas() throws Exception {
        Path pack = Files.createDirectories(this.directory.resolve("pack"));
        Path texture = Files.createDirectories(pack.resolve("assets/minecraft/textures/item")).resolve("stick.png");
        Files.write(texture, new byte[]{1, 2, 3});
        Path archives = this.directory.resolve("previews");
        ResourceSnapshots snapshots = new ResourceSnapshots(archives, archive -> { });

        Path first = capture(snapshots, pack, FLUIDS);
        assertEquals(first, capture(snapshots, pack, FLUIDS), "a reload that changed nothing the icons are drawn from");
        assertEquals(1, archiveCount(archives), "the same capture is not kept twice");

        Files.write(texture, new byte[]{4, 5, 6});
        Path saved = capture(snapshots, pack, FLUIDS);
        assertNotEquals(first, saved, "a texture saved into the pack");

        assertNotEquals(saved, capture(snapshots, pack, "{\"water\":{}}".getBytes(StandardCharsets.UTF_8)),
                "fluids that look different");
    }

    /** Captures {@code pack} as a reload reads it: its resources opened anew. */
    private static Path capture(ResourceSnapshots snapshots, Path pack, byte[] fluids) throws Exception {
        PackResources resources = new PathPackResources(
                new PackLocationInfo("file/pack", Component.literal("pack"), PackSource.DEFAULT, Optional.empty()), pack);
        try (MultiPackResourceManager manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(resources))) {
            return snapshots.capture(manager, List.of(resources), fluids);
        }
    }

    private static long archiveCount(Path directory) throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.toString().endsWith(".zip")).count();
        }
    }
}
