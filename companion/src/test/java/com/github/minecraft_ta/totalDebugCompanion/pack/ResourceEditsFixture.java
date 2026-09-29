package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;

import java.util.concurrent.Executor;

/** Resource edits as a project makes them: its pipeline and the game's packs of one location. */
public final class ResourceEditsFixture {
    private ResourceEditsFixture() {
    }

    public static ResourceEdits edits(GameLocation location, ChangeRecord record, ResourceOriginals originals, Executor writes,
                                      InstanceState state) {
        return new ResourceEdits(new ChangePipeline(location, record, writes), new GamePacks(location), originals, writes, state);
    }
}
