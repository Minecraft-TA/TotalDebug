package com.github.minecraft_ta.totaldebug.companionApp.chunkGrid;

public interface IChunkGridManager {

    int UNLOADED_CHUNK = 0;
    int SPAWN_CHUNK = 1;
    int LAZY_CHUNK = 2;

    void update();
}
