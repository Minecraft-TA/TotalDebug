package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity.ClassLink;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import java.util.ArrayList;
import java.util.List;

/** Describes what occupies a target on the side that resolved it. Called on the thread that owns the target. */
public final class SubjectIdentities {
    private SubjectIdentities() {
    }

    public static SubjectIdentity of(ScriptTarget target) {
        return switch (target) {
            case ScriptTarget.PlacedBlock block -> block(block.state(), block.blockEntity());
            case ScriptTarget.LiveEntity entity -> entity(entity.entity());
        };
    }

    public static SubjectIdentity block(BlockState state, BlockEntity blockEntity) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        List<ClassLink> classes = new ArrayList<>();
        classes.add(new ClassLink("Block", state.getBlock().getClass().getName()));
        if (blockEntity != null) {
            classes.add(new ClassLink("Block entity", blockEntity.getClass().getName()));
        }
        return new SubjectIdentity(
                SubjectIdentity.Kind.BLOCK,
                id.toString(),
                Fact.clip(state.getBlock().getName().getString()),
                modName(id.getNamespace()),
                classes,
                itemId(state.getBlock().asItem())
        );
    }

    public static SubjectIdentity entity(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        SpawnEggItem egg = SpawnEggItem.byId(entity.getType());
        return new SubjectIdentity(
                SubjectIdentity.Kind.ENTITY,
                id.toString(),
                Fact.clip(entity.getName().getString()),
                modName(id.getNamespace()),
                List.of(new ClassLink("Entity", entity.getClass().getName())),
                egg == null ? "" : itemId(egg)
        );
    }

    private static String itemId(Item item) {
        return item == Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static String modName(String namespace) {
        return Fact.clip(ModList.get().getModContainerById(namespace)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(namespace));
    }
}
