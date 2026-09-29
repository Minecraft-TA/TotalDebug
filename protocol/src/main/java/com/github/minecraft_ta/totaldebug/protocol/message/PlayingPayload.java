package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.minecraft_ta.totaldebug.protocol.Side;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Protocol-34 payload telling what the game plays: its menu, a singleplayer world of its instance, or a server. The game
 * sends it when the player joins or leaves one, and after Companion connects. See {@code docs/GAME_LOCATION.md}.
 */
public sealed interface PlayingPayload {
    /** No world is open. */
    record Menu() implements PlayingPayload {
    }

    /** A singleplayer world, run by the game's integrated server: {@code world} is its folder, absolute. */
    record Singleplayer(String world) implements PlayingPayload {
        public Singleplayer {
            Objects.requireNonNull(world, "world");
            if (world.isBlank()) throw new IllegalArgumentException("A singleplayer world needs its folder");
        }

        /** The world in {@code folder}, named as both endpoints name it: by its absolute, normalized path. */
        public static Singleplayer of(Path folder) {
            return new Singleplayer(folder.toAbsolutePath().normalize().toString());
        }
    }

    /**
     * A server on another machine: the address the player joined, whether it is a Realm, and whether the server has
     * TotalDebug, which runs {@link Side#SERVER} code and changes.
     */
    record Multiplayer(String address, boolean realms, boolean totalDebug) implements PlayingPayload {
        public Multiplayer {
            Objects.requireNonNull(address, "address");
        }
    }

    /**
     * The world a message bound to it names, the same on both endpoints: its folder for a singleplayer world, the
     * address for a server, empty in the menu. A request for a world the game no longer plays is refused.
     */
    default String identity() {
        return switch (this) {
            case Menu ignored -> "";
            case Singleplayer singleplayer -> "world " + singleplayer.world();
            case Multiplayer multiplayer -> "server " + multiplayer.address();
        };
    }

    static PlayingPayload read(ByteBufferInputStream input) {
        byte kind = input.readByte();
        return switch (kind) {
            case 0 -> new Menu();
            case 1 -> new Singleplayer(input.readString());
            case 2 -> new Multiplayer(input.readString(), input.readBoolean(), input.readBoolean());
            default -> throw new IllegalArgumentException("Invalid playing kind: " + kind);
        };
    }

    default void write(ByteBufferOutputStream output) {
        switch (this) {
            case Menu ignored -> output.writeByte((byte) 0);
            case Singleplayer singleplayer -> {
                output.writeByte((byte) 1);
                output.writeString(singleplayer.world());
            }
            case Multiplayer multiplayer -> {
                output.writeByte((byte) 2);
                output.writeString(multiplayer.address());
                output.writeBoolean(multiplayer.realms());
                output.writeBoolean(multiplayer.totalDebug());
            }
        }
    }
}
