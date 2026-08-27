package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.sun.jdi.ClassType;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Produces short, deliberate previews for common Minecraft and NeoForge values. */
final class DebuggerValuePreviewer {
    private static final int SUMMARY_LIMIT = 160;
    private static final List<Renderer> RENDERERS = List.of(
            renderer("net.minecraft.world.item.ItemStack",
                    "net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(getItem()).toString()"
                            + " + \" ×\" + getCount() + \"  \\\"\" + getHoverName().getString() + \"\\\"\""
                            + " + (getComponents().isEmpty() ? \"\" : \"  \" + getComponents().size() + \" components\")"),
            renderer("net.neoforged.neoforge.fluids.FluidStack",
                    "net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(getFluid()).toString()"
                            + " + \"  \" + getAmount() + \" mB\""),
            renderer("net.minecraft.world.level.block.state.BlockState",
                    "net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(getBlock()).toString()"
                            + " + (getValues().isEmpty() ? \"\" : \"  \" + ((Object) getValues()).toString())"),
            renderer("net.minecraft.core.BlockPos",
                    "\"x=\" + getX() + \", y=\" + getY() + \", z=\" + getZ()"),
            renderer("net.minecraft.world.level.ChunkPos",
                    "\"x=\" + x + \", z=\" + z"),
            renderer("net.minecraft.core.GlobalPos",
                    "dimension().location().toString() + \"  \" + pos().getX() + \", \""
                            + " + pos().getY() + \", \" + pos().getZ()"),
            renderer("net.minecraft.world.phys.Vec3",
                    "\"x=\" + x + \", y=\" + y + \", z=\" + z"),
            renderer("net.minecraft.world.phys.AABB",
                    "\"[\" + minX + \", \" + minY + \", \" + minZ + \"] to [\""
                            + " + maxX + \", \" + maxY + \", \" + maxZ + \"]\""),
            renderer("net.minecraft.resources.ResourceLocation", "toString()"),
            renderer("net.minecraft.resources.ResourceKey",
                    "registry().toString() + \" / \" + location().toString()"),
            renderer("net.minecraft.tags.TagKey", "\"#\" + location().toString()"),
            renderer("net.minecraft.core.Holder",
                    "unwrapKey().isPresent()"
                            + " ? ((net.minecraft.resources.ResourceKey) unwrapKey().get()).location().toString()"
                            + " : \"direct holder\""),
            renderer("net.minecraft.network.chat.Component", "\"\\\"\" + getString(120) + \"\\\"\""),
            renderer("net.minecraft.nbt.CompoundTag",
                    "size() + (size() == 1 ? \" entry\" : \" entries\")"),
            renderer("net.minecraft.world.entity.Entity",
                    "net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(getType()).toString()"
                            + " + \" #\" + getId() + \"  \" + getX() + \", \" + getY() + \", \" + getZ()"),
            renderer("net.minecraft.world.level.block.entity.BlockEntity",
                    "net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(getType()).toString()"
                            + " + \"  \" + getBlockPos().getX() + \", \" + getBlockPos().getY()"
                            + " + \", \" + getBlockPos().getZ()"),
            renderer("net.minecraft.world.level.Level", "dimension().location().toString()"),
            renderer("net.minecraft.world.item.Item",
                    "net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(this).toString()"),
            renderer("net.minecraft.world.level.block.Block",
                    "net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(this).toString()"),
            renderer("net.minecraft.world.level.material.Fluid",
                    "net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(this).toString()"),
            renderer("net.minecraft.world.entity.EntityType",
                    "net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(this).toString()"),
            renderer("net.minecraft.world.level.block.entity.BlockEntityType",
                    "net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(this).toString()")
    );

    @FunctionalInterface
    interface Evaluator {
        CompletableFuture<Value> evaluate(String expression, ObjectReference value, ThreadReference thread);
    }

    private final Evaluator evaluator;

    DebuggerValuePreviewer(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    CompletableFuture<DebugEngine.ValuePreview> preview(ObjectReference value, ThreadReference thread) {
        Renderer renderer = findRenderer(value.referenceType());
        if (renderer == null) {
            return CompletableFuture.completedFuture(DebugEngine.ValuePreview.NONE);
        }
        return this.evaluator.evaluate(renderer.expression(), value, thread).thenApply(DebuggerValuePreviewer::format);
    }

    private static DebugEngine.ValuePreview format(Value value) {
        if (!(value instanceof StringReference string)) {
            throw new IllegalStateException("Debugger value renderer must return a string");
        }
        String detail = string.value().replace('\r', ' ').replace('\n', ' ').trim();
        if (detail.isEmpty()) {
            return DebugEngine.ValuePreview.NONE;
        }
        String summary = detail.length() <= SUMMARY_LIMIT
                ? detail
                : detail.substring(0, SUMMARY_LIMIT - 1) + "…";
        return new DebugEngine.ValuePreview(summary, detail);
    }

    private static Renderer findRenderer(ReferenceType type) {
        for (Renderer renderer : RENDERERS) {
            if (hasType(type, renderer.typeName(), new HashSet<>())) {
                return renderer;
            }
        }
        return null;
    }

    private static boolean hasType(ReferenceType type, String target, Set<String> visited) {
        if (type == null || !visited.add(type.name())) {
            return false;
        }
        if (type.name().equals(target)) {
            return true;
        }
        if (type instanceof ClassType classType) {
            if (hasType(classType.superclass(), target, visited)) {
                return true;
            }
            for (InterfaceType iface : classType.interfaces()) {
                if (hasType(iface, target, visited)) {
                    return true;
                }
            }
        } else if (type instanceof InterfaceType interfaceType) {
            for (InterfaceType parent : interfaceType.superinterfaces()) {
                if (hasType(parent, target, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Renderer renderer(String typeName, String expression) {
        return new Renderer(typeName, expression);
    }

    private record Renderer(String typeName, String expression) {
    }
}
