package com.github.minecraft_ta.totaldebug.protocol.inspection;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Names an inspected thing by where it is found, never by a retained object. Its text form is used on the wire and
 * for copying, e.g. {@code block minecraft:overworld 12 64 -3}, {@code entity <uuid>},
 * {@code stack 7}, {@code mod mekanism},
 * {@code definition item mekanism:energy_tablet} or {@code definition mekanism:chemical mekanism:hydrogen}.
 */
public sealed interface SubjectRef {
    int MAX_TEXT_LENGTH = 320;
    Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

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
            case "stack" -> {
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Expected: stack <selection>");
                }
                try {
                    return new Stack(Long.parseLong(parts[1]));
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Invalid stack selection: " + parts[1], exception);
                }
            }
            case "mod" -> {
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Expected: mod <mod id>");
                }
                return new Mod(parts[1]);
            }
            case "definition" -> {
                if (parts.length != 3) {
                    throw new IllegalArgumentException("Expected: definition <registry> <id>");
                }
                return new Definition(parts[1].contains(":") ? parts[1] : Definition.MINECRAFT + parts[1], parts[2]);
            }
            default -> throw new IllegalArgumentException("Unknown subject kind: " + parts[0]);
        }
    }

    /** Parses a subject that game-side code can resolve: a block, an entity or a selected stack. */
    static Occurrence parseOccurrence(String text) {
        SubjectRef subject = parse(text);
        if (subject instanceof Occurrence occurrence) {
            return occurrence;
        }
        throw new IllegalArgumentException(subject.format() + " names a mod or definition, not something in the game");
    }

    private static int coordinate(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid coordinate: " + text, exception);
        }
    }

    /** Something in the game, which the game resolves: a block, an entity or a selected stack. */
    sealed interface Occurrence extends SubjectRef permits Block, Entity, Stack {
    }

    /** A block position in a dimension. */
    record Block(String dimension, int x, int y, int z) implements Occurrence {
        private static final int MAX_HORIZONTAL = 30_000_000;
        private static final int MAX_VERTICAL = 4_096;

        public Block {
            Objects.requireNonNull(dimension, "dimension");
            if (dimension.length() > 256 || !RESOURCE_ID.matcher(dimension).matches()) {
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
    record Entity(UUID uuid) implements Occurrence {
        public Entity {
            Objects.requireNonNull(uuid, "uuid");
        }

        @Override
        public String format() {
            return "entity " + this.uuid;
        }
    }

    /**
     * A stack selected in a screen, as the client kept it when it was selected: {@code selection} names the kept copy.
     * It is read on the client and shows the stack as it was, wherever the stack was shown.
     */
    record Stack(long selection) implements Occurrence {
        public Stack {
            if (selection < 1) {
                throw new IllegalArgumentException("Invalid stack selection: " + selection);
            }
        }

        @Override
        public String format() {
            return "stack " + this.selection;
        }
    }

    /** An installed mod, named by its mod id. */
    record Mod(String modId) implements SubjectRef {
        private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_]{1,63}");

        public Mod {
            Objects.requireNonNull(modId, "modId");
            if (!MOD_ID.matcher(modId).matches()) {
                throw new IllegalArgumentException("Invalid mod id: " + modId);
            }
        }

        @Override
        public String format() {
            return "mod " + this.modId;
        }
    }

    /**
     * An entry of a registry, such as a block type or a fluid, as opposed to one occurrence of it. {@code registry} is
     * the registry's id, such as {@code minecraft:block}; the text form leaves out the {@code minecraft} namespace of
     * a registry.
     */
    record Definition(String registry, String id) implements SubjectRef {
        static final String MINECRAFT = "minecraft:";

        public Definition {
            Objects.requireNonNull(registry, "registry");
            Objects.requireNonNull(id, "id");
            if (registry.length() > 256 || !RESOURCE_ID.matcher(registry).matches()) {
                throw new IllegalArgumentException("Invalid registry: " + registry);
            }
            if (id.length() > 256 || !RESOURCE_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Invalid registry id: " + id);
            }
            // The text form must parse again.
            String shown = registry.startsWith(MINECRAFT) ? registry.substring(MINECRAFT.length()) : registry;
            if (("definition " + shown + " " + id).length() > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("Definition too long: " + registry + " " + id);
            }
        }

        public String namespace() {
            return this.id.substring(0, this.id.indexOf(':'));
        }

        @Override
        public String format() {
            String registry = this.registry.startsWith(MINECRAFT) ? this.registry.substring(MINECRAFT.length()) : this.registry;
            return "definition " + registry + " " + this.id;
        }
    }
}
