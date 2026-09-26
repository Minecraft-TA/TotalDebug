package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import java.util.function.Supplier;

/**
 * Built-in reader for what a block or entity exposes through NeoForge's item, fluid and energy capabilities. It only
 * reads: no transfer is simulated, no chunk is loaded and containers whose loot is not generated yet are not read,
 * because reading their slots would generate it. A side of {@code null} reads the unsided handler, which may differ
 * from what automation sees on any particular face. Each part is read independently, so one failing handler does not
 * hide the others.
 */
public final class StorageReader {
    public static final int MAX_SLOTS = 96;
    public static final int MAX_TANKS = 32;

    private StorageReader() {
    }

    public static void read(ScriptTarget target, Direction side, ScriptFacts facts) {
        switch (target) {
            case ScriptTarget.PlacedBlock block -> readBlock(block, side, facts);
            case ScriptTarget.LiveEntity entity -> readEntity(entity.entity(), side, facts);
        }
    }

    private static void readBlock(ScriptTarget.PlacedBlock block, Direction side, ScriptFacts facts) {
        Level level = block.level();
        BlockPos pos = block.pos();
        BlockState state = block.state();
        facts.guarded("Items", () -> {
            String otherHalf = otherChestHalfUnreadable(level, pos, state);
            if (otherHalf != null) {
                facts.section("Items").text("Contents", otherHalf);
                return;
            }
            items(block.blockEntity(), () -> level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state,
                    block.blockEntity(), side), facts);
        });
        facts.guarded("Fluids", () -> fluids(level.getCapability(Capabilities.FluidHandler.BLOCK, pos, state,
                block.blockEntity(), side), facts));
        facts.guarded("Energy", () -> energy(level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, state,
                block.blockEntity(), side), facts));
        facts.guarded("Block state", () -> {
            if (!state.getValues().isEmpty()) {
                ScriptFacts.Section properties = facts.section("Block state");
                state.getValues().forEach((property, value) -> properties.text(property.getName(), value));
            }
        });
    }

    private static void readEntity(Entity entity, Direction side, ScriptFacts facts) {
        facts.guarded("Items", () -> items(entity, () -> side == null
                ? entity.getCapability(Capabilities.ItemHandler.ENTITY)
                : entity.getCapability(Capabilities.ItemHandler.ENTITY_AUTOMATION, side), facts));
        facts.guarded("Fluids", () -> fluids(entity.getCapability(Capabilities.FluidHandler.ENTITY, side), facts));
        facts.guarded("Energy", () -> energy(entity.getCapability(Capabilities.EnergyStorage.ENTITY, side), facts));
    }

    /**
     * Reports the slots of {@code owner}'s item handler, unless {@code owner} is a container whose loot is not
     * generated yet; then the handler is never requested, because reading any slot would generate the loot.
     */
    static void items(Object owner, Supplier<IItemHandler> handlerSource, ScriptFacts facts) {
        String pendingLoot = pendingLoot(owner);
        if (pendingLoot != null) {
            facts.section("Items").text("Contents", pendingLoot);
            return;
        }
        IItemHandler handler = handlerSource.get();
        if (handler == null) {
            return;
        }
        ScriptFacts.Section section = facts.section("Items");
        int slots = handler.getSlots();
        for (int slot = 0; slot < Math.min(slots, MAX_SLOTS); slot++) {
            section.stack("Slot " + slot, handler.getStackInSlot(slot));
        }
        if (slots > MAX_SLOTS) {
            section.text("Slots", slots + " (first " + MAX_SLOTS + " shown)");
        }
    }

    private static void fluids(IFluidHandler handler, ScriptFacts facts) {
        if (handler == null || handler.getTanks() == 0) {
            return;
        }
        ScriptFacts.Section section = facts.section("Fluids");
        int tanks = handler.getTanks();
        for (int tank = 0; tank < Math.min(tanks, MAX_TANKS); tank++) {
            section.fluid("Tank " + tank, handler.getFluidInTank(tank), handler.getTankCapacity(tank));
        }
        if (tanks > MAX_TANKS) {
            section.text("Tanks", tanks + " (first " + MAX_TANKS + " shown)");
        }
    }

    private static void energy(IEnergyStorage storage, ScriptFacts facts) {
        if (storage == null) {
            return;
        }
        facts.section("Energy")
                .bar("Stored", storage.getEnergyStored(), storage.getMaxEnergyStored(), "FE")
                .text("Accepts energy", storage.canReceive() ? "Yes" : "No")
                .text("Provides energy", storage.canExtract() ? "Yes" : "No");
    }

    /**
     * Why {@code owner}'s contents must not be read, or null. Block containers and container entities with a loot
     * table generate it on their first slot access, including {@code isEmpty()} for block entities.
     */
    static String pendingLoot(Object owner) {
        ResourceKey<LootTable> lootTable = switch (owner) {
            case RandomizableContainer container -> container.getLootTable();
            case ContainerEntity container -> container.getLootTable();
            case null, default -> null;
        };
        return lootTable == null ? null : notRead(lootTable);
    }

    /**
     * Why a double chest's item handler must not be looked up, or null. Resolving it combines both halves: an unloaded
     * other half would be loaded, and its pending loot generated on access.
     */
    static String otherChestHalfUnreadable(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
        if (!level.isLoaded(other)) {
            return "Not read: the other chest half is not loaded";
        }
        if (!(level.getBlockEntity(other) instanceof RandomizableContainer container)
                || container.getLootTable() == null) {
            return null;
        }
        return "Not read: loot " + container.getLootTable().location()
                + " in the other chest half is not generated yet";
    }

    private static String notRead(ResourceKey<LootTable> lootTable) {
        return "Not read: loot " + lootTable.location() + " is not generated yet";
    }
}
