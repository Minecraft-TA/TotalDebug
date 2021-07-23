package com.github.minecraft_ta.totaldebug.companionApp.chunkGrid;

public interface IChunkGridManager {

    byte UNLOADED_CHUNK = 0;
    byte SPAWN_CHUNK = 1;
    byte LAZY_CHUNK = 2;

    void update();
}
