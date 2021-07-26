package com.github.minecraft_ta.totaldebug.companionApp.chunkGrid;

public interface IChunkGridManager {

    byte SPAWN_CHUNK = 1;
    byte LAZY_CHUNK = 2;
    byte PLAYER_LOADED_CHUNK = 3;
    byte QUEUED_TO_UNLOAD_CHUNK = 4;

    void update();
}
