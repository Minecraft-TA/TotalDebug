package com.github.minecraft_ta.totaldebug.resource;

import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** A stack of packs as Companion is told it: the game client's resource packs, or a world's datapacks from its server. */
public final class PackStacks {
    private PackStacks() {
    }

    /**
     * The enabled packs of {@code repository}, lowest first, and the others its pack screen lists, with their folder or
     * file: {@code file/} packs in {@code folder}, {@code mod/} packs in their mod file. {@code features} are the world's,
     * for datapacks, or null.
     */
    public static PackStackPayload of(PackRepository repository, int format, Path folder, FeatureFlagSet features) {
        return new PackStackPayload(format, packs(repository.getSelectedPacks(), folder, features),
                packs(others(repository), folder, features));
    }

    /** The packs the game could enable but does not, as its pack screen lists them; the parts of another pack are not. */
    private static List<Pack> others(PackRepository repository) {
        Collection<Pack> selected = repository.getSelectedPacks();
        return repository.getAvailablePacks().stream().filter(pack -> !pack.isHidden() && !selected.contains(pack)).toList();
    }

    /** The packs with their folder or file, and what the game allows for each. */
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
