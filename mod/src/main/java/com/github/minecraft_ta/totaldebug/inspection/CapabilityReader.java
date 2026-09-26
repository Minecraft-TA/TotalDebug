package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BaseCapability;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.EntityCapability;

/**
 * Built-in reader listing every registered capability, including modded ones, that a block or entity exposes in the
 * requested context. Capabilities whose context is a side are queried with {@code side}; those without a context
 * only when no side is selected. Others are skipped because their context value is unknown.
 */
public final class CapabilityReader {
    private CapabilityReader() {
    }

    @SuppressWarnings("unchecked")
    public static void read(ScriptTarget target, Direction side, ScriptFacts facts) {
        ScriptFacts.Section section = facts.section("Capabilities");
        int exposed = 0;
        switch (target) {
            case ScriptTarget.PlacedBlock block -> {
                String chest = StorageReader.otherChestHalfUnreadable(block.level(), block.pos(), block.state());
                for (BlockCapability<?, ?> capability : BlockCapability.getAll()) {
                    if (!queryable(capability.contextClass(), side)) {
                        continue;
                    }
                    if (chest != null && capability == Capabilities.ItemHandler.BLOCK) {
                        section.text(capability.name().toString(), chest);
                        exposed++;
                        continue;
                    }
                    Object handler = ((BlockCapability<Object, Direction>) capability).getCapability(
                            block.level(), block.pos(), block.state(), block.blockEntity(), side);
                    exposed += report(section, capability, handler);
                }
            }
            case ScriptTarget.LiveEntity entity -> {
                for (EntityCapability<?, ?> capability : EntityCapability.getAll()) {
                    if (!queryable(capability.contextClass(), side)) {
                        continue;
                    }
                    Object handler = ((EntityCapability<Object, Direction>) capability)
                            .getCapability(entity.entity(), side);
                    exposed += report(section, capability, handler);
                }
            }
        }
        if (exposed == 0) {
            section.text("Exposed", "None");
        }
    }

    /**
     * Whether a capability with this context class can be queried for {@code side}. NeoForge gives capabilities
     * without a context the primitive {@code void.class}, which is only meaningful when no side is selected.
     */
    static boolean queryable(Class<?> contextClass, Direction side) {
        return contextClass == Direction.class || (contextClass == void.class && side == null);
    }

    private static int report(ScriptFacts.Section section, BaseCapability<?, ?> capability, Object handler) {
        if (handler == null) {
            return 0;
        }
        section.classLink(capability.name().toString(), handler.getClass());
        return 1;
    }
}
