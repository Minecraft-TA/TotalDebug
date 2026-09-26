package com.github.minecraft_ta.totaldebug.protocol.inspection;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Names an inspected thing by where it is found, never by a retained object. Its text form is used on the wire and
 * for copying, e.g. {@code block minecraft:overworld 12 64 -3}, {@code entity <uuid>},
 * {@code stack <uuid> inventory 4}, {@code stack <uuid> menu 3 12}, {@code mod mekanism},
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
                boolean inventory = parts.length == 4 && parts[2].equals("inventory");
                boolean menu = parts.length == 5 && parts[2].equals("menu");
                if (!inventory && !menu) {
                    throw new IllegalArgumentException("Expected: stack <player uuid> inventory <slot> or stack <player uuid> menu <id> <slot>");
                }
                UUID player;
                try {
                    player = UUID.fromString(parts[1]);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Invalid player UUID: " + parts[1], exception);
                }
                return inventory ? new Stack(player, Stack.INVENTORY, number(parts[3]))
                        : new Stack(player, number(parts[3]), number(parts[4]));
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

    /** Parses a subject that game-side code can resolve: a block, an entity or a stack a player holds. */
    static Occurrence parseOccurrence(String text) {
        SubjectRef subject = parse(text);
        if (subject instanceof Occurrence occurrence) {
            return occurrence;
        }
        throw new IllegalArgumentException(subject.format() + " names a mod or definition, not something in the game");
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid number: " + text, exception);
        }
    }

    private static int coordinate(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid coordinate: " + text, exception);
        }
    }

    /** Something in the game, which the game resolves: a block, an entity or a stack a player holds. */
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
     * The stack in a player's slot when it was selected: a slot of their inventory, or of the container they had open,
     * named by its menu id. The game follows that stack while the player keeps it, even when it moves.
     */
    record Stack(UUID player, int menu, int slot) implements Occurrence {
        public static final int INVENTORY = -1;
        private static final int MAX_SLOT = 4_096;

        public Stack {
            Objects.requireNonNull(player, "player");
            if (menu < INVENTORY) {
                throw new IllegalArgumentException("Invalid menu id: " + menu);
            }
            if (slot < 0 || slot > MAX_SLOT) {
                throw new IllegalArgumentException("Invalid slot: " + slot);
            }
        }

        @Override
        public String format() {
            return "stack " + this.player + (this.menu == INVENTORY ? " inventory " + this.slot : " menu " + this.menu + " " + this.slot);
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
