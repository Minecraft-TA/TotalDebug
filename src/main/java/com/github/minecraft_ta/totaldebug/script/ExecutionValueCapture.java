package com.github.minecraft_ta.totaldebug.script;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.minecraft_ta.totaldebug.script.ExecutionValue.Child;
import static com.github.minecraft_ta.totaldebug.script.ExecutionValue.ChildKind;
import static com.github.minecraft_ta.totaldebug.script.ExecutionValue.Kind;

/** Captures a bounded value graph while the script still owns its execution context. */
public final class ExecutionValueCapture {
    static final int MAX_DEPTH = 5;
    static final int MAX_ROOT_CHILDREN = 256;
    static final int MAX_NESTED_CHILDREN = 64;
    static final int MAX_NODES = 4_096;
    static final int MAX_PREVIEW_CHARACTERS = 320;
    private static final Limits FULL = new Limits(
            MAX_DEPTH, MAX_ROOT_CHILDREN, MAX_NESTED_CHILDREN, MAX_NODES
    );
    private ExecutionValueCapture() {
    }

    public static ExecutionValue capture(Object value) {
        Capture capture = new Capture(FULL);
        return capture.capture(value, 0, FULL.maxNodes());
    }

    private static final class Capture {
        private final Limits limits;
        private final IdentityHashMap<Object, Integer> identities = new IdentityHashMap<>();
        private int nextIdentity = 1;
        private int nodes;

        private Capture(Limits limits) {
            this.limits = limits;
        }

        private ExecutionValue capture(Object value, int depth, int nodeCeiling) {
            if (this.nodes >= nodeCeiling) {
                throw new IllegalStateException("Execution-value node budget exhausted");
            }
            this.nodes++;
            if (value == null) {
                return scalar("", "null", "", Kind.NULL);
            }

            Class<?> type = value.getClass();
            String typeName = type.getTypeName();
            if (value instanceof String text) {
                return scalar(typeName, text, "", Kind.STRING);
            }
            if (value instanceof Character character) {
                return scalar(typeName, character.toString(), "", Kind.CHARACTER);
            }
            if (value instanceof Boolean bool) {
                return scalar(typeName, bool.toString(), "", Kind.BOOLEAN);
            }
            if (value instanceof Number number) {
                return scalar(typeName, number.toString(), "", Kind.NUMBER);
            }
            if (value instanceof Enum<?> enumeration) {
                return scalar(typeName, enumeration.name(), "", Kind.ENUM);
            }
            if (value instanceof Class<?> represented) {
                return scalar(typeName, represented.getTypeName(), "", Kind.CLASS);
            }

            Integer previousIdentity = this.identities.get(value);
            if (previousIdentity != null) {
                return new ExecutionValue(
                        ExecutionText.complete(typeName),
                        ExecutionText.complete(simpleName(type)),
                        ExecutionText.complete("reference #" + previousIdentity),
                        Kind.REFERENCE,
                        previousIdentity,
                        0,
                        false,
                        List.of()
                );
            }
            int identity = this.nextIdentity++;
            this.identities.put(value, identity);

            ExecutionText preview = preview(value);
            if (value instanceof Optional<?> optional) {
                return optional(optional, typeName, preview, identity, depth, nodeCeiling);
            }
            if (type.isArray()) {
                return array(value, typeName, preview, identity, depth, nodeCeiling);
            }
            if (value instanceof Map<?, ?> map) {
                return map(map, typeName, preview, identity, depth, nodeCeiling);
            }
            if (value instanceof Collection<?> collection) {
                return collection(collection, typeName, preview, identity, depth, nodeCeiling);
            }
            return object(value, type, typeName, preview, identity, depth, nodeCeiling);
        }

        private ExecutionValue optional(
                Optional<?> optional,
                String type,
                ExecutionText preview,
                int identity,
                int depth,
                int nodeCeiling
        ) {
            if (optional.isEmpty()) {
                return composite(type, "Optional.empty", preview, Kind.OPTIONAL, identity, 0, false, List.of());
            }
            boolean truncated = shouldTruncate(depth, nodeCeiling);
            List<Child> children = truncated
                    ? List.of()
                    : List.of(Child.named("value", ChildKind.OPTIONAL_VALUE,
                    capture(optional.get(), depth + 1, nodeCeiling)));
            return composite(type, "Optional", preview, Kind.OPTIONAL, identity, 1, truncated, children);
        }

        private ExecutionValue array(
                Object array,
                String type,
                ExecutionText preview,
                int identity,
                int depth,
                int nodeCeiling
        ) {
            int length = Array.getLength(array);
            if (shouldTruncate(depth, nodeCeiling)) {
                return composite(type, simpleArrayName(array.getClass(), length), preview,
                        Kind.ARRAY, identity, length, length > 0, List.of());
            }
            int limit = childLimit(depth, length);
            List<Child> children = new ArrayList<>(limit);
            for (int index = 0; index < limit && this.nodes < nodeCeiling; index++) {
                int childCeiling = branchCeiling(nodeCeiling, limit - index, 1);
                children.add(Child.named("[" + index + "]", ChildKind.ARRAY_ELEMENT,
                        capture(Array.get(array, index), depth + 1, childCeiling)));
            }
            return composite(type, simpleArrayName(array.getClass(), length), preview,
                    Kind.ARRAY, identity, length, children.size() < length, children);
        }

        private ExecutionValue collection(
                Collection<?> collection,
                String type,
                ExecutionText preview,
                int identity,
                int depth,
                int nodeCeiling
        ) {
            int size = collection.size();
            if (shouldTruncate(depth, nodeCeiling)) {
                return composite(type, simpleName(collection.getClass()) + "(" + size + ")", preview,
                        Kind.COLLECTION, identity, size, size > 0, List.of());
            }
            int limit = childLimit(depth, size);
            List<Child> children = new ArrayList<>(limit);
            int index = 0;
            for (Object element : collection) {
                if (index >= limit || this.nodes >= nodeCeiling) {
                    break;
                }
                int childCeiling = branchCeiling(nodeCeiling, limit - index, 1);
                children.add(Child.named("[" + index + "]", ChildKind.COLLECTION_ELEMENT,
                        capture(element, depth + 1, childCeiling)));
                index++;
            }
            return composite(type, simpleName(collection.getClass()) + "(" + size + ")", preview,
                    Kind.COLLECTION, identity, size, children.size() < size, children);
        }

        private ExecutionValue map(
                Map<?, ?> map,
                String type,
                ExecutionText preview,
                int identity,
                int depth,
                int nodeCeiling
        ) {
            int size = map.size();
            if (shouldTruncate(depth, nodeCeiling)) {
                return composite(type, simpleName(map.getClass()) + "(" + size + ")", preview,
                        Kind.MAP, identity, size, size > 0, List.of());
            }
            int limit = childLimit(depth, size);
            List<Child> children = new ArrayList<>(limit);
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (index >= limit || nodeCeiling - this.nodes < 2) {
                    break;
                }
                int entryCeiling = branchCeiling(nodeCeiling, limit - index, 2);
                ExecutionValue key = capture(entry.getKey(), depth + 1, entryCeiling - 1);
                ExecutionValue entryValue = capture(entry.getValue(), depth + 1, entryCeiling);
                children.add(Child.mapEntry(
                        key,
                        entryValue
                ));
                index++;
            }
            return composite(type, simpleName(map.getClass()) + "(" + size + ")", preview,
                    Kind.MAP, identity, size, children.size() < size, children);
        }

        private ExecutionValue object(
                Object value,
                Class<?> type,
                String typeName,
                ExecutionText preview,
                int identity,
                int depth,
                int nodeCeiling
        ) {
            if (isPlatformType(type)) {
                return composite(typeName, simpleName(type), preview, Kind.OBJECT,
                        identity, 0, false, List.of());
            }
            RecordComponent[] components = type.isRecord() ? type.getRecordComponents() : null;
            List<Field> fields = components == null ? fields(type) : List.of();
            int total = components == null ? fields.size() : components.length;
            if (shouldTruncate(depth, nodeCeiling)) {
                return composite(typeName, simpleName(type), preview, Kind.OBJECT,
                        identity, total, total > 0, List.of());
            }

            List<Child> children = components == null
                    ? fieldChildren(value, fields, depth, nodeCeiling)
                    : recordChildren(value, components, depth, nodeCeiling);
            return composite(typeName, simpleName(type), preview, Kind.OBJECT,
                    identity, total, children.size() < total, children);
        }

        private List<Child> recordChildren(
                Object value,
                RecordComponent[] components,
                int depth,
                int nodeCeiling
        ) {
            int limit = childLimit(depth, components.length);
            List<Child> children = new ArrayList<>(limit);
            for (int index = 0; index < limit && this.nodes < nodeCeiling; index++) {
                RecordComponent component = components[index];
                Method accessor = component.getAccessor();
                int childCeiling = branchCeiling(nodeCeiling, limit - index, 1);
                try {
                    if (!accessor.canAccess(value) && !accessor.trySetAccessible()) {
                        this.nodes++;
                        children.add(inaccessible(component.getName(), ChildKind.RECORD_COMPONENT));
                    } else {
                        children.add(Child.named(component.getName(), ChildKind.RECORD_COMPONENT,
                                capture(accessor.invoke(value), depth + 1, childCeiling)));
                    }
                } catch (Throwable throwable) {
                    this.nodes++;
                    children.add(failed(component.getName(), ChildKind.RECORD_COMPONENT, throwable));
                }
            }
            return children;
        }

        private List<Child> fieldChildren(
                Object value,
                List<Field> fields,
                int depth,
                int nodeCeiling
        ) {
            int limit = childLimit(depth, fields.size());
            List<Child> children = new ArrayList<>(limit);
            for (int index = 0; index < limit && this.nodes < nodeCeiling; index++) {
                Field field = fields.get(index);
                int childCeiling = branchCeiling(nodeCeiling, limit - index, 1);
                try {
                    if (!field.canAccess(value) && !field.trySetAccessible()) {
                        this.nodes++;
                        children.add(inaccessible(field.getName(), ChildKind.FIELD));
                    } else {
                        children.add(Child.named(field.getName(), ChildKind.FIELD,
                                capture(field.get(value), depth + 1, childCeiling)));
                    }
                } catch (Throwable throwable) {
                    this.nodes++;
                    children.add(failed(field.getName(), ChildKind.FIELD, throwable));
                }
            }
            return children;
        }

        private boolean shouldTruncate(int depth, int nodeCeiling) {
            return depth >= this.limits.maxDepth() || this.nodes >= nodeCeiling;
        }

        private int childLimit(int depth, int size) {
            int configured = depth == 0
                    ? this.limits.maxRootChildren()
                    : this.limits.maxNestedChildren();
            return Math.min(size, configured);
        }

        private int branchCeiling(int nodeCeiling, int branchesRemaining, int minimumNodes) {
            int available = nodeCeiling - this.nodes;
            int branchNodes = Math.max(minimumNodes, available / branchesRemaining);
            return Math.min(nodeCeiling, this.nodes + branchNodes);
        }
    }

    private record Limits(
            int maxDepth,
            int maxRootChildren,
            int maxNestedChildren,
            int maxNodes
    ) {
    }

    private static ExecutionValue scalar(String type, String value, String preview, Kind kind) {
        return new ExecutionValue(
                ExecutionText.complete(type),
                ExecutionText.complete(value),
                previewText(preview),
                kind,
                0,
                0,
                false,
                List.of()
        );
    }

    private static ExecutionValue composite(
            String type,
            String value,
            ExecutionText preview,
            Kind kind,
            int identity,
            int totalChildren,
            boolean truncated,
            List<Child> children
    ) {
        return new ExecutionValue(
                ExecutionText.complete(type),
                ExecutionText.complete(value),
                preview,
                kind,
                identity,
                totalChildren,
                truncated,
                children
        );
    }

    private static Child inaccessible(String name, ChildKind kind) {
        return Child.named(name, kind, scalar("", "<inaccessible>", "", Kind.ERROR));
    }

    private static Child failed(String name, ChildKind kind, Throwable throwable) {
        String message = throwable.getMessage();
        return Child.named(name, kind, scalar(
                throwable.getClass().getTypeName(),
                "<unavailable>",
                message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message,
                Kind.ERROR
        ));
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            List<Field> declared = new ArrayList<>();
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    declared.add(field);
                }
            }
            declared.sort(Comparator.comparing(Field::getName));
            result.addAll(declared);
        }
        return result;
    }

    private static boolean isPlatformType(Class<?> type) {
        String packageName = type.getPackageName();
        return packageName.startsWith("java.")
                || packageName.startsWith("javax.")
                || packageName.startsWith("jdk.")
                || packageName.startsWith("sun.");
    }

    private static ExecutionText preview(Object value) {
        try {
            String result = switch (value) {
                case ItemStack stack -> BuiltInRegistries.ITEM.getKey(stack.getItem())
                        + " ×" + stack.getCount()
                        + "  \"" + stack.getHoverName().getString() + "\""
                        + (stack.getComponents().isEmpty()
                        ? "" : "  " + stack.getComponents().size() + " components");
                case FluidStack stack -> BuiltInRegistries.FLUID.getKey(stack.getFluid())
                        + "  " + stack.getAmount() + " mB";
                case BlockState state -> state.toString();
                case BlockPos pos -> "x=" + pos.getX() + ", y=" + pos.getY() + ", z=" + pos.getZ();
                case ChunkPos pos -> "x=" + pos.x + ", z=" + pos.z;
                case GlobalPos pos -> pos.dimension().location() + "  "
                        + pos.pos().getX() + ", " + pos.pos().getY() + ", " + pos.pos().getZ();
                case Vec3 pos -> "x=" + pos.x + ", y=" + pos.y + ", z=" + pos.z;
                case AABB box -> "[" + box.minX + ", " + box.minY + ", " + box.minZ + "] to ["
                        + box.maxX + ", " + box.maxY + ", " + box.maxZ + "]";
                case ResourceLocation location -> location.toString();
                case ResourceKey<?> key -> key.registry() + " / " + key.location();
                case TagKey<?> tag -> "#" + tag.location();
                case Holder<?> holder -> holder.unwrapKey()
                        .map(key -> key.location().toString())
                        .orElse("direct holder");
                case Component component -> "\"" + component.getString(120) + "\"";
                case CompoundTag tag -> tag.size() + (tag.size() == 1 ? " entry" : " entries");
                case Entity entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                        + " #" + entity.getId() + "  " + entity.getX() + ", " + entity.getY() + ", " + entity.getZ();
                case BlockEntity blockEntity -> BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType())
                        + "  " + blockEntity.getBlockPos().getX() + ", " + blockEntity.getBlockPos().getY()
                        + ", " + blockEntity.getBlockPos().getZ();
                case Level level -> level.dimension().location().toString();
                case Item item -> BuiltInRegistries.ITEM.getKey(item).toString();
                case Block block -> BuiltInRegistries.BLOCK.getKey(block).toString();
                case Fluid fluid -> BuiltInRegistries.FLUID.getKey(fluid).toString();
                case EntityType<?> entityType -> BuiltInRegistries.ENTITY_TYPE.getKey(entityType).toString();
                case BlockEntityType<?> blockEntityType ->
                        BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntityType).toString();
                default -> "";
            };
            return previewText(result);
        } catch (Throwable ignored) {
            return ExecutionText.empty();
        }
    }

    private static ExecutionText previewText(String value) {
        if (value == null || value.isEmpty()) {
            return ExecutionText.empty();
        }
        int end = Math.min(value.length(), MAX_PREVIEW_CHARACTERS);
        if (end > 0 && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        StringBuilder result = new StringBuilder(end);
        for (int index = 0; index < end; index++) {
            char character = value.charAt(index);
            result.append(character == '\r' || character == '\n' ? ' ' : character);
        }
        return new ExecutionText(result.toString(), value.length(), end < value.length());
    }

    private static String simpleArrayName(Class<?> type, int length) {
        return simpleName(type.getComponentType()) + "[" + length + "]";
    }

    private static String simpleName(Class<?> type) {
        String simple = type.getSimpleName();
        return simple.isBlank() ? type.getTypeName() : simple;
    }

}
