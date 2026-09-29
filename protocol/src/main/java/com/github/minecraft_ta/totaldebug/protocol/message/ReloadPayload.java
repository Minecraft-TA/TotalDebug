package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Protocol-37 payload asking for a reload of what edited resources need: the client's resources from the game client, or
 * the world's data, {@link Kind#DATA} alone, from its server through the relay, whose envelope names the world.
 * {@code managedResourcePack} and {@code managedDataPack} are the id of the pack Companion manages, such as
 * {@code file/TotalDebug}, among the resource packs and the datapacks, which is enabled at the top of that stack before
 * the reload; empty where no edit of the reload went into it. {@code watched} are the edited resource paths whose
 * problems the answer reports.
 */
public record ReloadPayload(int requestId, Set<Kind> kinds, String managedResourcePack, String managedDataPack,
                            List<String> watched) {
    public static final int MAX_WATCHED = 1_024;

    /** What to reload. */
    public enum Kind {
        /** Language files only; all resources when the managed pack is not enabled yet. */
        LANGUAGE,
        /** Every client resource. */
        RESOURCES,
        /** The server's data, as {@code /reload} does. */
        DATA,
        /** The pixels of textures, put in place where they are; all resources where that cannot show them. */
        TEXTURES
    }

    public ReloadPayload {
        kinds = Set.copyOf(kinds);
        if (kinds.isEmpty()) throw new IllegalArgumentException("Nothing to reload");
        Objects.requireNonNull(managedResourcePack, "managedResourcePack");
        Objects.requireNonNull(managedDataPack, "managedDataPack");
        if (kinds.contains(Kind.DATA) && (kinds.size() > 1 || !managedResourcePack.isEmpty())) {
            throw new IllegalArgumentException("The world's data reloads on its server, apart from the client's resources");
        }
        if (!kinds.contains(Kind.DATA) && !managedDataPack.isEmpty()) {
            throw new IllegalArgumentException("Only a data reload enables the managed datapack");
        }
        watched = List.copyOf(watched);
        if (watched.size() > MAX_WATCHED) throw new IllegalArgumentException("Too many watched paths");
    }

    public static ReloadPayload read(ByteBufferInputStream input) {
        int requestId = input.readInt();
        int mask = input.readInt();
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        for (Kind kind : Kind.values()) {
            if ((mask & (1 << kind.ordinal())) != 0) kinds.add(kind);
        }
        String managedResourcePack = input.readString();
        String managedDataPack = input.readString();
        int count = input.readInt();
        if (count < 0 || count > MAX_WATCHED) throw new IllegalArgumentException("Invalid watched path count: " + count);
        List<String> watched = new ArrayList<>(count);
        for (int index = 0; index < count; index++) watched.add(input.readString());
        return new ReloadPayload(requestId, kinds, managedResourcePack, managedDataPack, watched);
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.requestId);
        int mask = 0;
        for (Kind kind : this.kinds) mask |= 1 << kind.ordinal();
        output.writeInt(mask);
        output.writeString(this.managedResourcePack);
        output.writeString(this.managedDataPack);
        output.writeInt(this.watched.size());
        for (String path : this.watched) output.writeString(path);
    }
}
