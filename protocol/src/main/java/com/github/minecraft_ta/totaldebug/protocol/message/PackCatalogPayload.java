package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Protocol-22 payload announcing the pack catalog that belongs to a published runtime inventory. */
public record PackCatalogPayload(int state, String inventoryId, String catalogFile, String detail) {
    public static final int CAPTURING = 0;
    public static final int AVAILABLE = 1;
    public static final int FAILED = 2;

    public PackCatalogPayload {
        if (state < CAPTURING || state > FAILED) {
            throw new IllegalArgumentException("Invalid pack catalog state " + state);
        }
        if (state == AVAILABLE && (inventoryId.isBlank() || catalogFile.isBlank())) {
            throw new IllegalArgumentException("An available pack catalog needs its inventory id and file");
        }
    }

    public static PackCatalogPayload read(ByteBufferInputStream input) {
        return new PackCatalogPayload(input.readInt(), input.readString(), input.readString(), input.readString());
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.state);
        output.writeString(this.inventoryId);
        output.writeString(this.catalogFile);
        output.writeString(this.detail);
    }
}
