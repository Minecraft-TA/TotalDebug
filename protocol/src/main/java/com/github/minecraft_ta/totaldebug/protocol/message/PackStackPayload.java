package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-27 payload describing the game's enabled packs, lowest first, so the last pack wins. Data packs are empty
 * while no singleplayer world is open. {@code resourceFormat} and {@code dataFormat} are the pack formats of this
 * Minecraft version.
 */
public record PackStackPayload(int resourceFormat, int dataFormat, List<Pack> resourcePacks, List<Pack> dataPacks) {
    public static final int MAX_PACKS = 4_096;

    /** An enabled pack: its id such as {@code file/TotalDebug}, the title the game shows, and its folder or file, or empty. */
    public record Pack(String id, String title, String source) {
        public Pack {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(source, "source");
        }
    }

    public PackStackPayload {
        resourcePacks = List.copyOf(resourcePacks);
        dataPacks = List.copyOf(dataPacks);
        if (resourcePacks.size() > MAX_PACKS || dataPacks.size() > MAX_PACKS) throw new IllegalArgumentException("Too many packs");
    }

    public static PackStackPayload read(ByteBufferInputStream input) {
        return new PackStackPayload(input.readInt(), input.readInt(), readPacks(input), readPacks(input));
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.resourceFormat);
        output.writeInt(this.dataFormat);
        writePacks(output, this.resourcePacks);
        writePacks(output, this.dataPacks);
    }

    private static List<Pack> readPacks(ByteBufferInputStream input) {
        int count = input.readInt();
        if (count < 0 || count > MAX_PACKS) throw new IllegalArgumentException("Invalid pack count: " + count);
        List<Pack> packs = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            packs.add(new Pack(input.readString(), input.readString(), input.readString()));
        }
        return packs;
    }

    private static void writePacks(ByteBufferOutputStream output, List<Pack> packs) {
        output.writeInt(packs.size());
        for (Pack pack : packs) {
            output.writeString(pack.id());
            output.writeString(pack.title());
            output.writeString(pack.source());
        }
    }
}
