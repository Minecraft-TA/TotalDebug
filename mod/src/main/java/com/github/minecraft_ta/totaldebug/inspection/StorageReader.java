package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Built-in reader for what a block or entity exposes through NeoForge's item, fluid and energy capabilities. It only
 * reads: no transfer is simulated, no chunk is loaded and unopened loot containers are left untouched. A side of
 * {@code null} reads the unsided handler, which may differ from what automation sees on a particular face.
 */
public final class StorageReader {
    public static final int MAX_SLOTS = 96;

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
        BlockEntity blockEntity = block.blockEntity();

        String pendingLoot = pendingLoot(level, pos, state, blockEntity);
        if (pendingLoot != null) {
            facts.section("Items").text("Contents", pendingLoot);
        } else {
            items(level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state, blockEntity, side), facts);
        }
        fluids(level.getCapability(Capabilities.FluidHandler.BLOCK, pos, state, blockEntity, side), facts);
        energy(level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, state, blockEntity, side), facts);

        if (!state.getValues().isEmpty()) {
            ScriptFacts.Section properties = facts.section("Block state");
            state.getValues().forEach((property, value) -> properties.text(property.getName(), value));
        }
    }

    private static void readEntity(Entity entity, Direction side, ScriptFacts facts) {
        items(side == null
                ? entity.getCapability(Capabilities.ItemHandler.ENTITY)
                : entity.getCapability(Capabilities.ItemHandler.ENTITY_AUTOMATION, side), facts);
        fluids(entity.getCapability(Capabilities.FluidHandler.ENTITY, side), facts);
        energy(entity.getCapability(Capabilities.EnergyStorage.ENTITY, side), facts);
    }

    private static void items(IItemHandler handler, ScriptFacts facts) {
        if (handler == null) {
            return;
        }
        ScriptFacts.Section section = facts.section("Items");
        int slots = handler.getSlots();
        for (int slot = 0; slot < Math.min(slots, MAX_SLOTS); slot++) {
            section.stack("Slot " + slot, handler.getStackInSlot(slot));
        }
        if (slots > MAX_SLOTS) {
            facts.section("Items").text("Slots", slots + " (first " + MAX_SLOTS + " shown)");
        }
    }

    private static void fluids(IFluidHandler handler, ScriptFacts facts) {
        if (handler == null || handler.getTanks() == 0) {
            return;
        }
        ScriptFacts.Section section = facts.section("Fluids");
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            section.fluid("Tank " + tank, handler.getFluidInTank(tank), handler.getTankCapacity(tank));
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

    /** Reading a container with an unopened loot table would generate its loot, including the other chest half. */
    private static String pendingLoot(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (blockEntity instanceof RandomizableContainerBlockEntity container && container.getLootTable() != null) {
            return "Loot not generated yet; not read to avoid generating it";
        }
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            if (level.isLoaded(other)
                    && level.getBlockEntity(other) instanceof RandomizableContainerBlockEntity container
                    && container.getLootTable() != null) {
                return "Loot in the other chest half not generated yet; not read to avoid generating it";
            }
        }
        return null;
    }
}
