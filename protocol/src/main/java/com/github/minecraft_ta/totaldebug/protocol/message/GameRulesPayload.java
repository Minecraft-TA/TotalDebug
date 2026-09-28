package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Protocol-30 payload with the game rules of the singleplayer world the game has open, by name, as {@code /gamerule}
 * prints their values; empty while no world is open.
 */
public record GameRulesPayload(Map<String, String> rules) {
    public static final int MAX_RULES = 4_096;

    public GameRulesPayload {
        if (rules.size() > MAX_RULES) throw new IllegalArgumentException("Too many game rules");
        rules = Collections.unmodifiableMap(new TreeMap<>(rules));
    }

    public static GameRulesPayload read(ByteBufferInputStream input) {
        int count = input.readInt();
        if (count < 0 || count > MAX_RULES) throw new IllegalArgumentException("Invalid game rule count: " + count);
        Map<String, String> rules = new TreeMap<>();
        for (int index = 0; index < count; index++) rules.put(input.readString(), input.readString());
        return new GameRulesPayload(rules);
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.rules.size());
        this.rules.forEach((name, value) -> {
            output.writeString(name);
            output.writeString(value);
        });
    }
}
