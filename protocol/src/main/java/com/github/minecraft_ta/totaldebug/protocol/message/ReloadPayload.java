package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Protocol-24 payload asking the game to reload what edited resources need. {@code managedPack} is the id of the pack
 * Companion manages, such as {@code file/TotalDebug}, which the game enables at the top of its stacks before the
 * reload; empty for none. {@code watched} are the edited resource paths whose problems the answer reports.
 */
public record ReloadPayload(int requestId, Set<Kind> kinds, String managedPack, List<String> watched) {
    public static final int MAX_WATCHED = 1_024;

    /** What to reload. */
    public enum Kind {
        /** Language files only; all resources when the managed pack is not enabled yet. */
        LANGUAGE,
        /** Every client resource. */
        RESOURCES,
        /** The server's data, as {@code /reload} does. */
        DATA
    }

    public ReloadPayload {
        kinds = Set.copyOf(kinds);
        if (kinds.isEmpty()) throw new IllegalArgumentException("Nothing to reload");
        Objects.requireNonNull(managedPack, "managedPack");
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
        String managedPack = input.readString();
        int count = input.readInt();
        if (count < 0 || count > MAX_WATCHED) throw new IllegalArgumentException("Invalid watched path count: " + count);
        List<String> watched = new ArrayList<>(count);
        for (int index = 0; index < count; index++) watched.add(input.readString());
        return new ReloadPayload(requestId, kinds, managedPack, watched);
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.requestId);
        int mask = 0;
        for (Kind kind : this.kinds) mask |= 1 << kind.ordinal();
        output.writeInt(mask);
        output.writeString(this.managedPack);
        output.writeInt(this.watched.size());
        for (String path : this.watched) output.writeString(path);
    }
}
