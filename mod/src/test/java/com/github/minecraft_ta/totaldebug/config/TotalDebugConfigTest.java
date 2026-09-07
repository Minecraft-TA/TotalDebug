package com.github.minecraft_ta.totaldebug.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotalDebugConfigTest {
    @Test
    void usesTheSupportedClientAndServerDefaults() {
        assertTrue(TotalDebugConfig.CLIENT.useCompanionApp.getDefault());
        assertTrue(TotalDebugConfig.CLIENT.companionDevelopmentJar.getDefault().isEmpty());
        assertFalse(TotalDebugConfig.SERVER.enableScripts.getDefault());
        assertTrue(TotalDebugConfig.SERVER.enableScriptsOnlyForOp.getDefault());
    }

}
