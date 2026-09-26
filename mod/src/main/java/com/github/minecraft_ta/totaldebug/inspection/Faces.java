package com.github.minecraft_ta.totaldebug.inspection;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Names a block's faces for people. A block with a horizontal facing has its faces named relative to it, as machines
 * name them in their side configuration, with the compass direction beside the horizontal ones; other blocks have
 * compass names.
 */
final class Faces {
    private static final Set<Direction> HORIZONTAL = Set.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST);

    private Faces() {
    }

    /** The block's horizontal facing, or null when it has none. */
    static Direction facing(BlockState state) {
        for (var property : state.getProperties()) {
            if (state.getValue(property) instanceof Direction direction && direction.getAxis().isHorizontal()
                    && property.getName().endsWith("facing")) {
                return direction;
            }
        }
        return null;
    }

    /** The six faces in the order they are listed: front, back, left, right, top, bottom, or by compass. */
    static List<Direction> ordered(Direction facing) {
        return facing == null
                ? List.of(Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
                : List.of(facing, facing.getOpposite(), facing.getClockWise(), facing.getCounterClockWise(),
                        Direction.UP, Direction.DOWN);
    }

    static String name(Direction face, Direction facing) {
        String compass = capitalized(face.getSerializedName());
        if (face == Direction.UP) return facing == null ? compass : "Top";
        if (face == Direction.DOWN) return facing == null ? compass : "Bottom";
        if (facing == null) return compass;
        String relative = face == facing ? "Front" : face == facing.getOpposite() ? "Back"
                : face == facing.getClockWise() ? "Left" : "Right";
        return relative + " (" + face.getSerializedName() + ")";
    }

    /** A group of faces: every face, the four sides, or the faces by name in listing order. */
    static String group(List<Direction> faces, Direction facing) {
        if (faces.size() == 6) return "All faces";
        if (faces.size() == 4 && HORIZONTAL.containsAll(faces)) return "Sides";
        List<String> names = new ArrayList<>();
        for (Direction face : ordered(facing)) {
            if (faces.contains(face)) names.add(name(face, facing));
        }
        return String.join(", ", names);
    }

    private static String capitalized(String text) {
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }
}
