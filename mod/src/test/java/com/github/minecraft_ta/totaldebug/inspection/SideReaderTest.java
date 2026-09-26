package com.github.minecraft_ta.totaldebug.inspection;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SideReaderTest {
    @Test
    void facesThatBehaveLikeTheBlockWithoutASideAreOneGroup() {
        assertEquals(List.of(new SideReader.Group<>("All sides", "Takes and gives")),
                SideReader.group("Takes and gives", faces("Takes and gives", Map.of()), Direction.NORTH, String::equals));
    }

    @Test
    void aFurnaceListsItsSidesTopAndBottomRelativeToItsFacing() {
        Map<Direction, String> faces = faces("fuel", Map.of(Direction.UP, "input", Direction.DOWN, "output"));

        assertEquals(List.of(
                new SideReader.Group<>("Without a side", "all"),
                new SideReader.Group<>("Sides", "fuel"),
                new SideReader.Group<>("Top", "input"),
                new SideReader.Group<>("Bottom", "output")
        ), SideReader.group("all", faces, Direction.NORTH, String::equals));
    }

    @Test
    void aConfiguredMachineGroupsItsFacesByWhatTheyDo() {
        Map<Direction, String> faces = faces("input", Map.of(
                Direction.NORTH, "output",
                Direction.WEST, "energy",
                Direction.DOWN, "energy"));

        assertEquals(List.of(
                new SideReader.Group<>("Without a side", "read only"),
                new SideReader.Group<>("Front (east), Left (south), Top", "input"),
                new SideReader.Group<>("Back (west), Bottom", "energy"),
                new SideReader.Group<>("Right (north)", "output")
        ), SideReader.group("read only", faces, Direction.EAST, String::equals));
    }

    @Test
    void aBlockWithoutFacingUsesCompassNames() {
        Map<Direction, String> faces = faces("in", Map.of(Direction.UP, SideReader.NOT_EXPOSED));

        assertEquals(List.of(
                new SideReader.Group<>("Without a side", "closed"),
                new SideReader.Group<>("Up", SideReader.NOT_EXPOSED),
                new SideReader.Group<>("Down, North, South, West, East", "in")
        ), SideReader.group("closed", faces, null, String::equals));
    }

    @Test
    void accessNamesWhatTheStateCannotTell() {
        assertEquals("Takes and gives", SideReader.access(true, true));
        assertEquals("Takes", SideReader.access(true, false));
        assertEquals("Gives", SideReader.access(false, true));
        assertEquals("Neither takes nor gives", SideReader.access(false, false));
        assertEquals("Gives, full", SideReader.access(null, true));
        assertEquals("Takes nothing, empty", SideReader.access(false, null));
    }

    @Test
    void capabilitiesSayWhereOnlySomeFacesExposeThem() {
        assertEquals("", CapabilityReader.where(true, List.of(Direction.values()), Direction.NORTH));
        assertEquals("only without a side", CapabilityReader.where(true, List.of(), Direction.NORTH));
        assertEquals("only through faces", CapabilityReader.where(false, List.of(Direction.values()), null));
        assertEquals("only on Top", CapabilityReader.where(false, List.of(Direction.UP), Direction.NORTH));
        assertEquals("without a side and on the four sides", CapabilityReader.where(true,
                List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST), Direction.NORTH));
    }

    /** Every face showing {@code rest}, except those in {@code differing}. */
    private static Map<Direction, String> faces(String rest, Map<Direction, String> differing) {
        Map<Direction, String> faces = new EnumMap<>(Direction.class);
        for (Direction face : Direction.values()) faces.put(face, differing.getOrDefault(face, rest));
        return faces;
    }
}
