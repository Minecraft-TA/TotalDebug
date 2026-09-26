package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.items.IItemHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

class StorageReaderTest {
    private static final ResourceKey<LootTable> DUNGEON = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.withDefaultNamespace("chests/simple_dungeon"));
    private static final String NOT_READ = "Not read: loot minecraft:chests/simple_dungeon is not generated yet";

    @Test
    void anUnopenedContainerEntityIsNeverReadSoItsLootStaysUngenerated() {
        List<String> calls = new ArrayList<>();
        ContainerEntity minecart = container(ContainerEntity.class, DUNGEON, calls);

        List<FactSection> sections = readItems(minecart);

        assertEquals(List.of(new FactSection("Items", List.of(Fact.text("Contents", NOT_READ)), 1)), sections);
        assertEquals(List.of("getLootTable"), calls);
    }

    @Test
    void anUnopenedBlockContainerIsNeverReadIncludingContainersOutsideTheCommonBaseClass() {
        List<String> calls = new ArrayList<>();
        RandomizableContainer pot = container(RandomizableContainer.class, DUNGEON, calls);

        List<FactSection> sections = readItems(pot);

        assertEquals(List.of(new FactSection("Items", List.of(Fact.text("Contents", NOT_READ)), 1)), sections);
        assertEquals(List.of("getLootTable"), calls, "isEmpty() and slot reads would generate the loot");
    }

    @Test
    void aContainerWhoseLootWasGeneratedIsRead() {
        ContainerEntity opened = container(ContainerEntity.class, null, new ArrayList<>());
        ScriptFacts facts = new ScriptFacts(text -> { });
        boolean[] requested = {false};

        StorageReader.items(opened, () -> {
            requested[0] = true;
            return null;
        }, facts);

        assertEquals(true, requested[0]);
        assertNull(StorageReader.pendingLoot(new Object()));
        assertNull(StorageReader.pendingLoot(null));
    }

    private static List<FactSection> readItems(Object owner) {
        ScriptFacts facts = new ScriptFacts(text -> { });
        StorageReader.items(owner, StorageReaderTest::handlerThatMustNotBeRequested, facts);
        return facts.snapshot();
    }

    private static IItemHandler handlerThatMustNotBeRequested() {
        return fail("The item handler of a container with pending loot was requested");
    }

    /** A container whose only answer is its loot table; any other call is recorded as an access. */
    private static <T> T container(Class<T> type, ResourceKey<LootTable> lootTable, List<String> calls) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            calls.add(method.getName());
            if (method.getName().equals("getLootTable")) {
                return lootTable;
            }
            throw new AssertionError("Unexpected container access: " + method.getName());
        }));
    }
}
