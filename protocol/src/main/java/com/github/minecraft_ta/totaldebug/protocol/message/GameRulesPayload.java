package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Protocol-30 payload with the game rules of the singleplayer world the game has open, by name, as {@code /gamerule}
 * prints their values, and the folder name of that world; empty while no world is open.
 */
public record GameRulesPayload(String world, Map<String, String> rules) {
    public static final int MAX_RULES = 4_096;

    public GameRulesPayload {
        Objects.requireNonNull(world, "world");
        if (rules.size() > MAX_RULES) throw new IllegalArgumentException("Too many game rules");
        rules = Collections.unmodifiableMap(new TreeMap<>(rules));
    }

    /** Whether these are the rules of the world in the folder named {@code world}. */
    public boolean of(String world) {
        return !this.rules.isEmpty() && this.world.equals(world);
    }

    public static GameRulesPayload read(ByteBufferInputStream input) {
        String world = input.readString();
        int count = input.readInt();
        if (count < 0 || count > MAX_RULES) throw new IllegalArgumentException("Invalid game rule count: " + count);
        Map<String, String> rules = new TreeMap<>();
        for (int index = 0; index < count; index++) rules.put(input.readString(), input.readString());
        return new GameRulesPayload(world, rules);
    }

    public void write(ByteBufferOutputStream output) {
        output.writeString(this.world);
        output.writeInt(this.rules.size());
        this.rules.forEach((name, value) -> {
            output.writeString(name);
            output.writeString(value);
        });
    }
}
