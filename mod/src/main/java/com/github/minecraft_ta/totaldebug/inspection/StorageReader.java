package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Built-in reader for what a block or entity holds, through NeoForge's item, fluid and energy capabilities, read
 * without a side. A block that only exposes a handler through its faces is read through the face exposing the most.
 * When a block's faces expose its slots differently, the slots are shown per side as {@link SideReader} groups them.
 * No chunk is loaded and containers whose loot is not generated yet are not read, because reading their slots would
 * generate it. Each part is read independently, so one failing handler does not hide the others.
 */
public final class StorageReader {
    public static final int MAX_SLOTS = 96;
    public static final int MAX_TANKS = 32;

    /** A block's handler and the face it was read through, which is null for the handler without a side. */
    record Found<T>(T handler, Direction face) {
    }

    private StorageReader() {
    }

    public static void read(ScriptTarget target, ScriptFacts facts) {
        switch (target) {
            case ScriptTarget.PlacedBlock block -> readBlock(block, facts);
            case ScriptTarget.LiveEntity entity -> readEntity(entity.entity(), facts);
            case ScriptTarget.HeldStack held -> readStack(held.stack(), facts);
        }
    }

    private static void readBlock(ScriptTarget.PlacedBlock block, ScriptFacts facts) {
        facts.guarded("Items", () -> {
            String otherHalf = otherChestHalfUnreadable(block.level(), block.pos(), block.state());
            if (otherHalf != null) {
                facts.section("Items").text("Contents", otherHalf);
                return;
            }
            String pending = pendingLoot(block.blockEntity());
            if (pending != null) {
                facts.section("Items").text("Contents", pending);
                return;
            }
            Found<IItemHandler> found = contents(block, Capabilities.ItemHandler.BLOCK, IItemHandler::getSlots);
            List<SideReader.Group<List<SideReader.Slot>>> sides = SideReader.items(block);
            if (sides.size() == 1) {
                items(block.blockEntity(), () -> through(block, "Items", found, facts), facts);
                return;
            }
            // The slots without a side are the contents, shown in their group; contents read through a face come first.
            if (found.face() != null) {
                items(block.blockEntity(), () -> through(block, "Items", found, facts), facts);
            } else if (found.handler().getSlots() > MAX_SLOTS) {
                facts.section("Items").text("Slots", found.handler().getSlots() + " (first " + MAX_SLOTS + " shown)");
            }
            for (SideReader.Group<List<SideReader.Slot>> group : sides) {
                SideReader.writeItems(group, facts.section("Items"));
            }
        });
        facts.guarded("Fluids", () -> fluids(through(block, "Fluids",
                contents(block, Capabilities.FluidHandler.BLOCK, IFluidHandler::getTanks), facts), facts));
        facts.guarded("Energy", () -> energy(through(block, "Energy",
                contents(block, Capabilities.EnergyStorage.BLOCK, IEnergyStorage::getMaxEnergyStored), facts), facts));
    }

    private static void readEntity(Entity entity, ScriptFacts facts) {
        facts.guarded("Items", () -> items(entity, () -> entity.getCapability(Capabilities.ItemHandler.ENTITY), facts));
        facts.guarded("Fluids", () -> fluids(entity.getCapability(Capabilities.FluidHandler.ENTITY, null), facts));
        facts.guarded("Energy", () -> energyWithFlags(entity.getCapability(Capabilities.EnergyStorage.ENTITY, null), facts));
    }

    /** What a stack holds itself, such as a shulker box's items, a bucket's fluid or a battery's energy. */
    private static void readStack(ItemStack stack, ScriptFacts facts) {
        facts.guarded("Items", () -> items(stack, () -> stack.getCapability(Capabilities.ItemHandler.ITEM), facts));
        facts.guarded("Fluids", () -> fluids(stack.getCapability(Capabilities.FluidHandler.ITEM), facts));
        facts.guarded("Energy", () -> energyWithFlags(stack.getCapability(Capabilities.EnergyStorage.ITEM), facts));
    }

    /** Energy of something without sides, with whether it takes and gives energy at all. */
    private static void energyWithFlags(IEnergyStorage storage, ScriptFacts facts) {
        energy(storage, facts);
        if (storage != null) {
            facts.section("Energy")
                    .text("Accepts energy", storage.canReceive() ? "Yes" : "No")
                    .text("Provides energy", storage.canExtract() ? "Yes" : "No");
        }
    }

    /**
     * The block's handler without a side, or when it has none, the handler of the face whose {@code size} is largest.
     * The handler is null when no face exposes one either.
     */
    static <T> Found<T> contents(ScriptTarget.PlacedBlock block, BlockCapability<T, Direction> capability,
                                 ToIntFunction<T> size) {
        T unsided = handler(block, capability, null);
        if (unsided != null) return new Found<>(unsided, null);
        Found<T> best = new Found<>(null, null);
        for (Direction face : Direction.values()) {
            T handler = handler(block, capability, face);
            if (handler != null && (best.handler() == null || size.applyAsInt(handler) > size.applyAsInt(best.handler()))) {
                best = new Found<>(handler, face);
            }
        }
        return best;
    }

    /** The block's handler through {@code face}; a double chest whose other half is not loaded has no item handler. */
    static <T> T handler(ScriptTarget.PlacedBlock block, BlockCapability<T, Direction> capability, Direction face) {
        if (capability == Capabilities.ItemHandler.BLOCK && otherChestHalfUnloaded(block.level(), block.pos(), block.state())) {
            return null;
        }
        return capability.getCapability(block.level(), block.pos(), block.state(), block.blockEntity(), face);
    }

    /** Why the block's items must not be read, or null when they may be. */
    static String unreadableItems(ScriptTarget.PlacedBlock block) {
        String pending = pendingLoot(block.blockEntity());
        return pending != null ? pending : otherChestHalfUnreadable(block.level(), block.pos(), block.state());
    }

    /** The found handler, noting the face it was read through when it is not the handler without a side. */
    private static <T> T through(ScriptTarget.PlacedBlock block, String section, Found<T> found, ScriptFacts facts) {
        if (found.handler() != null && found.face() != null) {
            facts.section(section).text("Read through", Faces.name(found.face(), Faces.facing(block.state())));
        }
        return found.handler();
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
        facts.section("Energy").bar("Stored", storage.getEnergyStored(), storage.getMaxEnergyStored(), "FE");
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

    /** Whether the block is half of a double chest whose other half is not loaded. */
    private static boolean otherChestHalfUnloaded(Level level, BlockPos pos, BlockState state) {
        return state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                && !level.isLoaded(pos.relative(ChestBlock.getConnectedDirection(state)));
    }

    /**
     * Why a double chest's item handler must not be looked up, or null. Resolving it combines both halves: an unloaded
     * other half would be loaded, and its pending loot generated on access.
     */
    static String otherChestHalfUnreadable(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        if (otherChestHalfUnloaded(level, pos, state)) {
            return "Not read: the other chest half is not loaded";
        }
        BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
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
