package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-29 payload describing the game's packs: the enabled ones, lowest first, so the last pack wins, and the others
 * it could enable, in the order its pack screen lists them. Data packs are empty while no singleplayer world is open.
 * {@code resourceFormat} and {@code dataFormat} are the pack formats of this Minecraft version.
 */
public record PackStackPayload(int resourceFormat, int dataFormat, List<Pack> resourcePacks, List<Pack> dataPacks,
                               List<Pack> otherResourcePacks, List<Pack> otherDataPacks) {
    public static final int MAX_PACKS = 4_096;

    /** The game must keep the pack enabled, such as Minecraft's own resources. */
    public static final int REQUIRED = 1;
    /** The pack keeps its place, at the top or the bottom. */
    public static final int FIXED = 1 << 1;
    /** The pack is part of another, such as a mod's resources within the mods' pack, and not listed on its own. */
    public static final int HIDDEN = 1 << 2;
    /** The pack was made for another version of the game. */
    public static final int INCOMPATIBLE = 1 << 3;
    /** The pack requests features the world does not have, so it cannot be enabled in it. */
    public static final int MISSING_FEATURES = 1 << 4;

    /**
     * A pack: its id such as {@code file/TotalDebug}, the title the game shows, its folder or file, or empty, and what
     * the game allows for it, as the flags above.
     */
    public record Pack(String id, String title, String source, int flags) {
        public Pack {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(source, "source");
        }

        public Pack(String id, String title, String source) {
            this(id, title, source, 0);
        }

        public boolean is(int flag) {
            return (this.flags & flag) != 0;
        }
    }

    public PackStackPayload {
        resourcePacks = List.copyOf(resourcePacks);
        dataPacks = List.copyOf(dataPacks);
        otherResourcePacks = List.copyOf(otherResourcePacks);
        otherDataPacks = List.copyOf(otherDataPacks);
        for (List<Pack> packs : List.of(resourcePacks, dataPacks, otherResourcePacks, otherDataPacks)) {
            if (packs.size() > MAX_PACKS) throw new IllegalArgumentException("Too many packs");
        }
    }

    /** The enabled packs only, as a game names them that lists no others. */
    public PackStackPayload(int resourceFormat, int dataFormat, List<Pack> resourcePacks, List<Pack> dataPacks) {
        this(resourceFormat, dataFormat, resourcePacks, dataPacks, List.of(), List.of());
    }

    public static PackStackPayload read(ByteBufferInputStream input) {
        return new PackStackPayload(input.readInt(), input.readInt(), readPacks(input), readPacks(input), readPacks(input),
                readPacks(input));
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.resourceFormat);
        output.writeInt(this.dataFormat);
        writePacks(output, this.resourcePacks);
        writePacks(output, this.dataPacks);
        writePacks(output, this.otherResourcePacks);
        writePacks(output, this.otherDataPacks);
    }

    private static List<Pack> readPacks(ByteBufferInputStream input) {
        int count = input.readInt();
        if (count < 0 || count > MAX_PACKS) throw new IllegalArgumentException("Invalid pack count: " + count);
        List<Pack> packs = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            packs.add(new Pack(input.readString(), input.readString(), input.readString(), input.readInt()));
        }
        return packs;
    }

    private static void writePacks(ByteBufferOutputStream output, List<Pack> packs) {
        output.writeInt(packs.size());
        for (Pack pack : packs) {
            output.writeString(pack.id());
            output.writeString(pack.title());
            output.writeString(pack.source());
            output.writeInt(pack.flags());
        }
    }
}
