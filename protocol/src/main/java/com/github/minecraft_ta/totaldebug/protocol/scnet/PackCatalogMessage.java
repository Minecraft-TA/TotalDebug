package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.PackCatalogPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.Objects;

/** Tells Companion that the game is capturing, has published or failed to publish its pack catalog file. */
public final class PackCatalogMessage extends AbstractMessage {
    public static final int CAPTURING = PackCatalogPayload.CAPTURING;
    public static final int AVAILABLE = PackCatalogPayload.AVAILABLE;
    public static final int FAILED = PackCatalogPayload.FAILED;
    private PackCatalogPayload payload;

    public PackCatalogMessage() {
    }

    private PackCatalogMessage(PackCatalogPayload payload) {
        this.payload = payload;
    }

    public static PackCatalogMessage capturing(String inventoryId) {
        return new PackCatalogMessage(new PackCatalogPayload(CAPTURING, Objects.requireNonNull(inventoryId), "", ""));
    }

    public static PackCatalogMessage available(String inventoryId, String file) {
        return new PackCatalogMessage(new PackCatalogPayload(AVAILABLE, Objects.requireNonNull(inventoryId),
                Objects.requireNonNull(file), ""));
    }

    public static PackCatalogMessage failed(String inventoryId, String detail) {
        return new PackCatalogMessage(new PackCatalogPayload(FAILED, Objects.requireNonNull(inventoryId), "",
                Objects.requireNonNull(detail)));
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = PackCatalogPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public int state() {
        return this.payload.state();
    }

    public String inventoryId() {
        return this.payload.inventoryId();
    }

    public String catalogFile() {
        return this.payload.catalogFile();
    }

    public String detail() {
        return this.payload.detail();
    }
}
