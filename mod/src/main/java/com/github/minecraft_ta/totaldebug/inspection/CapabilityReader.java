package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BaseCapability;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.EntityCapability;

/**
 * Built-in reader listing every registered capability, including modded ones, that a block or entity exposes in the
 * requested context. Only capabilities whose context is a side (or none, for entities) can be queried generically;
 * others are skipped because their context value is unknown.
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
                for (BlockCapability<?, ?> capability : BlockCapability.getAll()) {
                    if (capability.contextClass() != Direction.class) {
                        continue;
                    }
                    Object handler = ((BlockCapability<Object, Direction>) capability).getCapability(
                            block.level(), block.pos(), block.state(), block.blockEntity(), side);
                    exposed += report(section, capability, handler);
                }
            }
            case ScriptTarget.LiveEntity entity -> {
                for (EntityCapability<?, ?> capability : EntityCapability.getAll()) {
                    Object handler;
                    if (capability.contextClass() == Direction.class) {
                        handler = ((EntityCapability<Object, Direction>) capability).getCapability(entity.entity(), side);
                    } else if (capability.contextClass() == Void.class && side == null) {
                        handler = ((EntityCapability<Object, Void>) capability).getCapability(entity.entity(), null);
                    } else {
                        continue;
                    }
                    exposed += report(section, capability, handler);
                }
            }
        }
        if (exposed == 0) {
            section.text("Exposed", "None");
        }
    }

    private static int report(ScriptFacts.Section section, BaseCapability<?, ?> capability, Object handler) {
        if (handler == null) {
            return 0;
        }
        section.text(capability.name().toString(), handler.getClass().getName());
        return 1;
    }
}
