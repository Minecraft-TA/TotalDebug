package com.github.minecraft_ta.totaldebug.client.resource;

import com.github.minecraft_ta.totaldebug.client.companion.ChangePublisher;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.flag.FeatureFlagSet;
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
    private final Path gameDirectory;
    private final ChangePublisher<PackStackPayload> publisher;

    public PackStackPublisher(Path gameDirectory, Consumer<PackStackPayload> publish) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory");
        this.publisher = new ChangePublisher<>(this::capture, publish);
    }

    /** Publishes the stacks again at the next check, such as for a newly connected Companion. */
    public void republish() {
        this.publisher.republish();
    }

    /** Client thread only. */
    public void tick() {
        this.publisher.tick();
    }

    private PackStackPayload capture() {
        Minecraft minecraft = Minecraft.getInstance();
        PackRepository resourceRepository = minecraft.getResourcePackRepository();
        Path resourceFolder = this.gameDirectory.resolve("resourcepacks");
        List<PackStackPayload.Pack> resources = packs(resourceRepository.getSelectedPacks(), resourceFolder, null);
        List<PackStackPayload.Pack> otherResources = packs(others(resourceRepository), resourceFolder, null);
        IntegratedServer server = minecraft.getSingleplayerServer();
        List<PackStackPayload.Pack> data = List.of();
        List<PackStackPayload.Pack> otherData = List.of();
        if (server != null) {
            Path dataFolder = server.getWorldPath(LevelResource.DATAPACK_DIR);
            FeatureFlagSet features = server.getWorldData().enabledFeatures();
            data = packs(server.getPackRepository().getSelectedPacks(), dataFolder, features);
            otherData = packs(others(server.getPackRepository()), dataFolder, features);
        }
        return new PackStackPayload(SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES),
                SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA), resources, data, otherResources, otherData);
    }

    /** The packs the game could enable but does not, as its pack screen lists them; the parts of another pack are not. */
    private static List<Pack> others(PackRepository repository) {
        Collection<Pack> selected = repository.getSelectedPacks();
        return repository.getAvailablePacks().stream().filter(pack -> !pack.isHidden() && !selected.contains(pack)).toList();
    }

    /**
     * The packs with their folder or file, {@code file/} packs in {@code folder} and {@code mod/} packs in their mod
     * file, and what the game allows for each; {@code features} are the world's, for datapacks, or null.
     */
    private static List<PackStackPayload.Pack> packs(Collection<Pack> listed, Path folder, FeatureFlagSet features) {
        List<PackStackPayload.Pack> packs = new ArrayList<>();
        for (Pack pack : listed) {
            String id = pack.getId();
            String source = "";
            if (id.startsWith("file/")) {
                source = folder.resolve(id.substring("file/".length())).toAbsolutePath().normalize().toString();
            } else if (id.startsWith("mod/")) {
                IModFileInfo file = ModList.get().getModFileById(id.substring("mod/".length()));
                if (file != null) source = file.getFile().getFilePath().toAbsolutePath().normalize().toString();
            }
            int flags = (pack.isRequired() ? PackStackPayload.REQUIRED : 0) | (pack.isFixedPosition() ? PackStackPayload.FIXED : 0)
                    | (pack.isHidden() ? PackStackPayload.HIDDEN : 0)
                    | (pack.getCompatibility().isCompatible() ? 0 : PackStackPayload.INCOMPATIBLE)
                    | (features != null && !pack.getRequestedFeatures().isSubsetOf(features) ? PackStackPayload.MISSING_FEATURES : 0);
            packs.add(new PackStackPayload.Pack(id, pack.getTitle().getString(), source, flags));
        }
        return packs;
    }
}
