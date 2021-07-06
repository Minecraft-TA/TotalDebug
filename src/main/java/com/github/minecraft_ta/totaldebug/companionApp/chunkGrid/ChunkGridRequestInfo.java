package com.github.minecraft_ta.totaldebug.companionApp.chunkGrid;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import io.netty.buffer.ByteBuf;

public class ChunkGridRequestInfo {

    public static final ChunkGridRequestInfo INVALID = new ChunkGridRequestInfo();

    private int minChunkX;
    private int minChunkZ;
    private int maxChunkX;
    private int maxChunkZ;

    private int dimension;

    public ChunkGridRequestInfo() {
    }

    public ChunkGridRequestInfo(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, int dimension) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
        this.dimension = dimension;
    }

    public void toBytes(ByteBufferOutputStream buf) {
        buf.writeInt(this.minChunkX);
        buf.writeInt(this.minChunkZ);
        buf.writeInt(this.maxChunkX);
        buf.writeInt(this.maxChunkZ);
        buf.writeInt(this.dimension);
    }

    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.minChunkX);
        buf.writeInt(this.minChunkZ);
        buf.writeInt(this.maxChunkX);
        buf.writeInt(this.maxChunkZ);
        buf.writeInt(this.dimension);
    }

    public static ChunkGridRequestInfo fromBytes(ByteBufferInputStream buf) {
        return new ChunkGridRequestInfo(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static ChunkGridRequestInfo fromBytes(ByteBuf buf) {
        return new ChunkGridRequestInfo(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
    }

    public int getMinChunkX() {
        return minChunkX;
    }

    public void setMinChunkX(int minChunkX) {
        this.minChunkX = minChunkX;
    }

    public int getMinChunkZ() {
        return minChunkZ;
    }

    public void setMinChunkZ(int minChunkZ) {
        this.minChunkZ = minChunkZ;
    }

    public int getMaxChunkX() {
        return maxChunkX;
    }

    public void setMaxChunkX(int maxChunkX) {
        this.maxChunkX = maxChunkX;
    }

    public int getMaxChunkZ() {
        return maxChunkZ;
    }

    public void setMaxChunkZ(int maxChunkZ) {
        this.maxChunkZ = maxChunkZ;
    }

    public int getWidth() {
        return this.maxChunkX - this.minChunkX;
    }

    public int getHeight() {
        return this.maxChunkZ - this.minChunkZ;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }
}
