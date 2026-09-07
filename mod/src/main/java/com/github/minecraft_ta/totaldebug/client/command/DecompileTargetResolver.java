package com.github.minecraft_ta.totaldebug.client.command;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

final class DecompileTargetResolver implements DecompileClientCommand.TargetResolver {
    private final ClassLoader classLoader;
    private final Function<EntityType<?>, Class<?>> entityClassResolver;

    private DecompileTargetResolver(
            ClassLoader classLoader,
            Function<EntityType<?>, Class<?>> entityClassResolver
    ) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.entityClassResolver = Objects.requireNonNull(entityClassResolver, "entityClassResolver");
    }

    static DecompileTargetResolver runtime() {
        return new DecompileTargetResolver(
                TotalDebug.class.getClassLoader(),
                entityType -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.level == null) {
                        return null;
                    }
                    Entity entity = entityType.create(minecraft.level);
                    return entity == null ? null : entity.getClass();
                }
        );
    }

    @Override
    public Collection<ResourceLocation> blockIds() {
        return BuiltInRegistries.BLOCK.keySet();
    }

    @Override
    public Optional<Class<?>> block(ResourceLocation id) {
        return registeredValue(BuiltInRegistries.BLOCK, id).map(Block::getClass);
    }

    @Override
    public Collection<ResourceLocation> itemIds() {
        return BuiltInRegistries.ITEM.keySet();
    }

    @Override
    public Optional<Class<?>> item(ResourceLocation id) {
        return registeredValue(BuiltInRegistries.ITEM, id).map(Item::getClass);
    }

    @Override
    public Collection<ResourceLocation> entityIds() {
        return BuiltInRegistries.ENTITY_TYPE.keySet();
    }

    @Override
    public Optional<Class<?>> entity(ResourceLocation id) {
        Optional<EntityType<?>> entityType = registeredValue(BuiltInRegistries.ENTITY_TYPE, id);
        if (entityType.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(this.entityClassResolver.apply(entityType.get()));
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.warn("Unable to construct entity type {} for decompilation", id, exception);
            return Optional.empty();
        }
    }

    @Override
    public Collection<ResourceLocation> blockEntityIds() {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet();
    }

    @Override
    public Optional<Class<?>> blockEntity(ResourceLocation id) {
        Optional<BlockEntityType<?>> blockEntityType = registeredValue(BuiltInRegistries.BLOCK_ENTITY_TYPE, id);
        if (blockEntityType.isEmpty()) {
            return Optional.empty();
        }

        BlockEntityType<?> type = blockEntityType.get();
        Optional<Block> validBlock = type.getValidBlocks().stream()
                .min(Comparator.comparing(block -> String.valueOf(BuiltInRegistries.BLOCK.getKey(block))));
        if (validBlock.isEmpty()) {
            return Optional.empty();
        }

        try {
            Block block = validBlock.get();
            BlockEntity blockEntity = type.create(BlockPos.ZERO, block.defaultBlockState());
            return blockEntity == null ? Optional.empty() : Optional.of(blockEntity.getClass());
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.warn("Unable to construct block entity type {} for decompilation", id, exception);
            return Optional.empty();
        }
    }

    @Override
    public CompletableFuture<List<String>> suggestedClasses(String input) {
        return RuntimeClassNames.CATALOG.thenApply(catalog -> catalog.suggest(input));
    }

    @Override
    public Optional<Class<?>> namedClass(String binaryName) {
        try {
            return Optional.of(Class.forName(binaryName, false, this.classLoader));
        } catch (ClassNotFoundException | LinkageError exception) {
            return Optional.empty();
        }
    }

    private static <T> Optional<T> registeredValue(Registry<T> registry, ResourceLocation id) {
        if (!registry.containsKey(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(registry.get(id));
    }

    private static final class RuntimeClassNames {
        private static final CompletableFuture<ClassNameCatalog> CATALOG = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return ClassNameCatalog.scanRuntime();
                    } catch (IOException exception) {
                        throw new CompletionException(exception);
                    }
                },
                Util.backgroundExecutor()
        ).whenComplete((catalog, exception) -> {
            if (exception != null) {
                TotalDebug.LOGGER.error("Unable to build the runtime class-name catalog", exception);
            }
        });

        private RuntimeClassNames() {
        }
    }
}
