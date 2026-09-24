package com.github.minecraft_ta.totaldebug.protocol.inspection;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Names an inspected thing by where it is found, never by a retained object. Its text form is used on the wire and
 * for copying, e.g. {@code block minecraft:overworld 12 64 -3} or {@code entity <uuid>}.
 */
public sealed interface SubjectRef {
    int MAX_TEXT_LENGTH = 320;

    String format();

    static SubjectRef parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Subject text is too long");
        }
        String[] parts = text.strip().split(" ");
        switch (parts[0]) {
            case "block" -> {
                if (parts.length != 5) {
                    throw new IllegalArgumentException("Expected: block <dimension> <x> <y> <z>");
                }
                return new Block(parts[1], coordinate(parts[2]), coordinate(parts[3]), coordinate(parts[4]));
            }
            case "entity" -> {
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Expected: entity <uuid>");
                }
                try {
                    return new Entity(UUID.fromString(parts[1]));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Invalid entity UUID: " + parts[1], exception);
                }
            }
            default -> throw new IllegalArgumentException("Unknown subject kind: " + parts[0]);
        }
    }

    private static int coordinate(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid coordinate: " + text, exception);
        }
    }

    /** A block position in a dimension. */
    record Block(String dimension, int x, int y, int z) implements SubjectRef {
        private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
        private static final int MAX_HORIZONTAL = 30_000_000;
        private static final int MAX_VERTICAL = 4_096;

        public Block {
            Objects.requireNonNull(dimension, "dimension");
            if (dimension.length() > 256 || !DIMENSION.matcher(dimension).matches()) {
                throw new IllegalArgumentException("Invalid dimension: " + dimension);
            }
            if (Math.abs((long) x) > MAX_HORIZONTAL || Math.abs((long) z) > MAX_HORIZONTAL
                    || Math.abs((long) y) > MAX_VERTICAL) {
                throw new IllegalArgumentException("Block position is outside the world limits");
            }
        }

        @Override
        public String format() {
            return "block " + this.dimension + " " + this.x + " " + this.y + " " + this.z;
        }
    }

    /** An entity in any loaded dimension. */
    record Entity(UUID uuid) implements SubjectRef {
        public Entity {
            Objects.requireNonNull(uuid, "uuid");
        }

        @Override
        public String format() {
            return "entity " + this.uuid;
        }
    }
}
