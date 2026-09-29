package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/**
 * Protocol-35 payload telling the state of a file the game prepares for Companion: being prepared, ready at
 * {@code file}, or failed with {@code detail}. {@code inventoryId} is the runtime inventory the file belongs to, empty
 * for item icons, which follow the resource packs instead. The game tells each change, and the latest state of each kind
 * again after every handshake.
 */
public record PreparedFilePayload(Kind kind, State state, String inventoryId, String file, String detail) {
    /** Which file. */
    public enum Kind {
        /** The classes the running game loads, which Companion indexes and compiles scripts against. */
        RUNTIME_INVENTORY,
        /** The items, blocks and other content of the mods and packs, with their translated names. */
        PACK_CATALOG,
        /** An archive of the winning models and textures, from which Companion draws item icons. */
        ITEM_ICONS
    }

    public enum State {
        PREPARING,
        READY,
        FAILED
    }

    public PreparedFilePayload {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(inventoryId, "inventoryId");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(detail, "detail");
        if (state == State.READY && (file.isBlank() || (kind != Kind.ITEM_ICONS && inventoryId.isBlank()))) {
            throw new IllegalArgumentException("A ready " + kind + " needs its file and inventory id");
        }
    }

    public static PreparedFilePayload preparing(Kind kind, String inventoryId, String detail) {
        return new PreparedFilePayload(kind, State.PREPARING, inventoryId, "", detail);
    }

    public static PreparedFilePayload ready(Kind kind, String inventoryId, String file) {
        return new PreparedFilePayload(kind, State.READY, inventoryId, file, "");
    }

    public static PreparedFilePayload failed(Kind kind, String inventoryId, String detail) {
        return new PreparedFilePayload(kind, State.FAILED, inventoryId, "", detail);
    }

    public static PreparedFilePayload read(ByteBufferInputStream input) {
        int kind = input.readInt();
        if (kind < 0 || kind >= Kind.values().length) throw new IllegalArgumentException("Invalid prepared file kind: " + kind);
        int state = input.readInt();
        if (state < 0 || state >= State.values().length) throw new IllegalArgumentException("Invalid prepared file state: " + state);
        return new PreparedFilePayload(Kind.values()[kind], State.values()[state], input.readString(), input.readString(),
                input.readString());
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.kind.ordinal());
        output.writeInt(this.state.ordinal());
        output.writeString(this.inventoryId);
        output.writeString(this.file);
        output.writeString(this.detail);
    }
}
