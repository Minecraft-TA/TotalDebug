package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * How a block's items, fluids and energy behave for automation without a side and through each face. Every check
 * simulates with what the block already holds, so nothing is moved and nothing is guessed: an empty slot or storage
 * cannot tell whether it gives, and a full one whether it takes more. When the sides differ, the block without a
 * side and each group of faces that behave alike get a row; faces are named as {@link Faces} does.
 */
public final class SideReader {
    static final String NOT_EXPOSED = "Not exposed";
    static final String WITHOUT_A_SIDE = "Without a side";
    static final String ALL_SIDES = "All sides";

    /** The block without a side, or a group of faces, and what it exposes. */
    record Group<V>(String label, V view) {
    }

    /** A slot as a side exposes it; {@code takes} and {@code gives} are null where its state cannot tell. */
    record Slot(ItemStack stack, Boolean takes, Boolean gives) {
        boolean same(Slot other) {
            return ItemStack.matches(this.stack, other.stack) && Objects.equals(this.takes, other.takes)
                    && Objects.equals(this.gives, other.gives);
        }
    }

    private SideReader() {
    }

    /** The rows for a block's fluids and energy, after their contents. Items are reported with their contents. */
    public static void read(ScriptTarget target, ScriptFacts facts) {
        if (!(target instanceof ScriptTarget.PlacedBlock block)) return;
        facts.guarded("Fluids", () -> report(groups(block, Capabilities.FluidHandler.BLOCK, SideReader::fluids,
                NOT_EXPOSED, String::equals), "Fluids", facts));
        facts.guarded("Energy", () -> report(groups(block, Capabilities.EnergyStorage.BLOCK, SideReader::energy,
                NOT_EXPOSED, String::equals), "Energy", facts));
    }

    /** The slots the block exposes without a side and through each face, grouped; one group when all sides are alike. */
    static List<Group<List<Slot>>> items(ScriptTarget.PlacedBlock block) {
        return groups(block, Capabilities.ItemHandler.BLOCK, SideReader::slots, List.of(), SideReader::sameSlots);
    }

    /** Writes a group of slots as one row, or that the side exposes none. */
    /** Writes a group's row: its first {@code limit} slots, or that it exposes none. */
    static void writeItems(Group<List<Slot>> group, ScriptFacts.Section section, int limit) {
        if (group.view().isEmpty()) {
            section.text(group.label(), NOT_EXPOSED);
            return;
        }
        for (Slot slot : group.view().subList(0, Math.min(limit, group.view().size()))) {
            section.slot(group.label(), slot.stack(), slot.takes(), slot.gives());
        }
    }

    private static void report(List<Group<String>> groups, String section, ScriptFacts facts) {
        if (groups.size() == 1 && groups.getFirst().view().equals(NOT_EXPOSED)) return;
        for (Group<String> group : groups) facts.section(section).text(group.label(), group.view());
    }

    private static <T, V> List<Group<V>> groups(ScriptTarget.PlacedBlock block, BlockCapability<T, Direction> capability,
                                              Function<T, V> view, V absent, BiPredicate<V, V> same) {
        T unsided = StorageReader.handler(block, capability, null);
        Map<Direction, V> faces = new EnumMap<>(Direction.class);
        for (Direction face : Direction.values()) {
            T handler = StorageReader.handler(block, capability, face);
            faces.put(face, handler == null ? absent : view.apply(handler));
        }
        return group(unsided == null ? absent : view.apply(unsided), faces, Faces.facing(block.state()), same);
    }

    /**
     * One group for all sides when every face behaves like the block without a side; otherwise the block without a
     * side followed by the faces grouped by behaviour, in listing order.
     */
    static <V> List<Group<V>> group(V unsided, Map<Direction, V> faces, Direction facing, BiPredicate<V, V> same) {
        if (faces.values().stream().allMatch(view -> same.test(unsided, view))) {
            return List.of(new Group<>(ALL_SIDES, unsided));
        }
        List<V> views = new ArrayList<>();
        List<List<Direction>> members = new ArrayList<>();
        for (Direction face : Faces.ordered(facing)) {
            V view = faces.get(face);
            int index = 0;
            while (index < views.size() && !same.test(views.get(index), view)) index++;
            if (index == views.size()) {
                views.add(view);
                members.add(new ArrayList<>());
            }
            members.get(index).add(face);
        }
        List<Group<V>> groups = new ArrayList<>();
        groups.add(new Group<>(WITHOUT_A_SIDE, unsided));
        for (int index = 0; index < views.size(); index++) {
            groups.add(new Group<>(Faces.group(members.get(index), facing), views.get(index)));
        }
        return groups;
    }

    private static List<Slot> slots(IItemHandler handler) {
        List<Slot> slots = new ArrayList<>();
        for (int slot = 0; slot < Math.min(handler.getSlots(), StorageReader.MAX_SLOTS); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) {
                slots.add(new Slot(stack, null, null));
                continue;
            }
            boolean full = stack.getCount() >= Math.min(handler.getSlotLimit(slot), stack.getMaxStackSize());
            Boolean takes = full ? null : handler.insertItem(slot, stack.copyWithCount(1), true).isEmpty();
            slots.add(new Slot(stack, takes, !handler.extractItem(slot, 1, true).isEmpty()));
        }
        return slots;
    }

    private static boolean sameSlots(List<Slot> first, List<Slot> second) {
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            if (!first.get(index).same(second.get(index))) return false;
        }
        return true;
    }

    /** Each tank's fluid and what it does, such as {@code Water: takes and gives}, then the empty tanks. */
    private static String fluids(IFluidHandler handler) {
        if (handler.getTanks() == 0) return NOT_EXPOSED;
        List<String> tanks = new ArrayList<>();
        int empty = 0;
        for (int tank = 0; tank < Math.min(handler.getTanks(), StorageReader.MAX_TANKS); tank++) {
            FluidStack fluid = handler.getFluidInTank(tank);
            if (fluid.isEmpty()) {
                empty++;
                continue;
            }
            Boolean takes = fluid.getAmount() >= handler.getTankCapacity(tank) ? null
                    : handler.fill(fluid.copyWithAmount(1), IFluidHandler.FluidAction.SIMULATE) > 0;
            boolean gives = !handler.drain(fluid.copyWithAmount(1), IFluidHandler.FluidAction.SIMULATE).isEmpty();
            tanks.add(fluid.getHoverName().getString() + ": " + access(takes, gives).toLowerCase(Locale.ROOT));
        }
        if (empty > 0) tanks.add(empty + (empty == 1 ? " empty tank" : " empty tanks"));
        return String.join(", ", tanks);
    }

    /** Simulates with the largest amount, since storages converting units round a small amount down to nothing. */
    private static String energy(IEnergyStorage storage) {
        int stored = storage.getEnergyStored();
        if (storage.getMaxEnergyStored() <= 0) return "No capacity";
        Boolean takes = stored >= storage.getMaxEnergyStored() ? null : storage.receiveEnergy(Integer.MAX_VALUE, true) > 0;
        Boolean gives = stored == 0 ? null : storage.extractEnergy(Integer.MAX_VALUE, true) > 0;
        return access(takes, gives);
    }

    /** What something takes and gives in words; a part its state cannot tell is left out and the state named. */
    static String access(Boolean takes, Boolean gives) {
        if (takes == null) return Boolean.TRUE.equals(gives) ? "Gives, full" : "Gives nothing, full";
        if (gives == null) return takes ? "Takes, empty" : "Takes nothing, empty";
        return takes ? (gives ? "Takes and gives" : "Takes") : (gives ? "Gives" : "Neither takes nor gives");
    }
}
