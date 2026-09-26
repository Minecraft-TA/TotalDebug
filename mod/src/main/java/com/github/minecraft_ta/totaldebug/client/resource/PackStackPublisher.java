package com.github.minecraft_ta.totaldebug.client.resource;

import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Tells Companion which resource packs and singleplayer data packs are enabled, whenever that changes or Companion
 * connects again. Checked once a second on the client thread.
 */
public final class PackStackPublisher {
    private static final int CHECK_TICKS = 20;

    private final Path gameDirectory;
    private final Consumer<PackStackPayload> publish;
    private PackStackPayload published;
    private int ticks;

    public PackStackPublisher(Path gameDirectory, Consumer<PackStackPayload> publish) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory");
        this.publish = Objects.requireNonNull(publish, "publish");
    }

    /** Publishes the stacks again at the next check, such as for a newly connected Companion. */
    public synchronized void republish() {
        this.published = null;
        this.ticks = CHECK_TICKS;
    }

    /** Client thread only. */
    public void tick() {
        PackStackPayload current;
        synchronized (this) {
            if (++this.ticks < CHECK_TICKS) return;
            this.ticks = 0;
        }
        current = capture();
        synchronized (this) {
            if (current.equals(this.published)) return;
            this.published = current;
        }
        this.publish.accept(current);
    }

    private PackStackPayload capture() {
        Minecraft minecraft = Minecraft.getInstance();
        List<PackStackPayload.Pack> resources = packs(minecraft.getResourcePackRepository().getSelectedPacks(),
                this.gameDirectory.resolve("resourcepacks"));
        IntegratedServer server = minecraft.getSingleplayerServer();
        List<PackStackPayload.Pack> data = server == null ? List.of()
                : packs(server.getPackRepository().getSelectedPacks(), server.getWorldPath(LevelResource.DATAPACK_DIR));
        return new PackStackPayload(SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES),
                SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA), resources, data);
    }

    /** The packs with their folder or file: {@code file/} packs in {@code folder}, {@code mod/} packs in their mod file. */
    private static List<PackStackPayload.Pack> packs(Collection<Pack> selected, Path folder) {
        List<PackStackPayload.Pack> packs = new ArrayList<>();
        for (Pack pack : selected) {
            String id = pack.getId();
            String source = "";
            if (id.startsWith("file/")) {
                source = folder.resolve(id.substring("file/".length())).toAbsolutePath().normalize().toString();
            } else if (id.startsWith("mod/")) {
                IModFileInfo file = ModList.get().getModFileById(id.substring("mod/".length()));
                if (file != null) source = file.getFile().getFilePath().toAbsolutePath().normalize().toString();
            }
            packs.add(new PackStackPayload.Pack(id, pack.getTitle().getString(), source));
        }
        return packs;
    }
}
