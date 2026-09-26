package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BaseCapability;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.capabilities.ItemCapability;
import java.util.ArrayList;
import java.util.List;

/**
 * Built-in reader listing every registered capability, including modded ones, that a block or entity exposes, once
 * each. A block's capability whose context is a side is asked without a side and through each face; one that only
 * some of them expose says where. Capabilities without a context are asked without one, and others are skipped
 * because their context value is unknown. An entity is asked without a side, and a stack without a context.
 */
public final class CapabilityReader {
    private CapabilityReader() {
    }

    @SuppressWarnings("unchecked")
    public static void read(ScriptTarget target, ScriptFacts facts) {
        ScriptFacts.Section section = facts.section("Capabilities");
        int exposed = 0;
        switch (target) {
            case ScriptTarget.PlacedBlock block -> {
                Direction facing = Faces.facing(block.state());
                for (BlockCapability<?, ?> capability : BlockCapability.getAll()) {
                    if (!queryable(capability.contextClass())) continue;
                    var sided = (BlockCapability<Object, Direction>) capability;
                    Object unsided = StorageReader.handler(block, sided, null);
                    if (capability.contextClass() == void.class) {
                        exposed += report(section, capability, unsided, "");
                        continue;
                    }
                    Object shown = unsided;
                    List<Direction> faces = new ArrayList<>();
                    for (Direction face : Direction.values()) {
                        Object handler = StorageReader.handler(block, sided, face);
                        if (handler == null) continue;
                        faces.add(face);
                        if (shown == null) shown = handler;
                    }
                    exposed += report(section, capability, shown, where(unsided != null, faces, facing));
                }
            }
            case ScriptTarget.HeldStack held -> {
                for (ItemCapability<?, ?> capability : ItemCapability.getAll()) {
                    if (capability.contextClass() != void.class) continue;
                    Object handler = ((ItemCapability<Object, Object>) capability).getCapability(held.stack(), null);
                    exposed += report(section, capability, handler, "");
                }
            }
            case ScriptTarget.LiveEntity entity -> {
                for (EntityCapability<?, ?> capability : EntityCapability.getAll()) {
                    if (!queryable(capability.contextClass())) continue;
                    Object handler = ((EntityCapability<Object, Direction>) capability).getCapability(entity.entity(), null);
                    exposed += report(section, capability, handler, "");
                }
            }
        }
        if (exposed == 0) {
            section.text("Exposed", "None");
        }
    }

    /**
     * Whether a capability with this context class can be queried: those with a side, and those without a context,
     * to which NeoForge gives the primitive {@code void.class}.
     */
    static boolean queryable(Class<?> contextClass) {
        return contextClass == Direction.class || contextClass == void.class;
    }

    /** Where a sided capability is exposed, or empty when it is exposed without a side and on every face. */
    static String where(boolean unsided, List<Direction> faces, Direction facing) {
        if (faces.isEmpty()) return unsided ? "only without a side" : "";
        if (faces.size() == 6) return unsided ? "" : "only through faces";
        String group = Faces.group(faces, facing);
        String on = group.equals("Sides") ? "the four sides" : group;
        return unsided ? "without a side and on " + on : "only on " + on;
    }

    private static int report(ScriptFacts.Section section, BaseCapability<?, ?> capability, Object handler, String where) {
        if (handler == null) {
            return 0;
        }
        section.classLink(capability.name().toString(), handler.getClass(), where);
        return 1;
    }
}
