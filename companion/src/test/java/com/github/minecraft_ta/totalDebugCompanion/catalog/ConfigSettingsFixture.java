package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;

/** Configuration settings for tests: written through a pipeline that runs each change at once. */
public final class ConfigSettingsFixture {
    private ConfigSettingsFixture() {
    }

    public static ConfigSettings of(GameLocation location, ChangeRecord record) {
        return new ConfigSettings(new ConfigChanges(location, record), new ChangePipeline(location, record, Runnable::run));
    }
}
