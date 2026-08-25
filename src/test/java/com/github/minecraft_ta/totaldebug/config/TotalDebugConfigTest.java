package com.github.minecraft_ta.totaldebug.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotalDebugConfigTest {
    @Test
    void keepsTheLegacyDefaultsForRetainedOptions() {
        assertTrue(TotalDebugConfig.CLIENT.useCompanionApp.getDefault());
        assertTrue(TotalDebugConfig.CLIENT.companionDevelopmentJar.getDefault().isEmpty());
        assertTrue(TotalDebugConfig.CLIENT.blockedPacketClasses.getDefault().isEmpty());
        assertFalse(TotalDebugConfig.SERVER.enableScripts.getDefault());
        assertTrue(TotalDebugConfig.SERVER.enableScriptsOnlyForOp.getDefault());
    }

    @Test
    void acceptsJavaBinaryNamesUsedByPacketClasses() {
        List<String> validNames = List.of(
                "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData",
                "example.network.Outer$NestedPacket",
                "mod.network.Packet_2"
        );

        assertTrue(validNames.stream().allMatch(TotalDebugConfig::isValidPacketClassName));
    }

    @Test
    void rejectsBlankOrMalformedPacketClassNames() {
        List<Object> invalidNames = List.of(
                "",
                "contains a space",
                ".leading.Dot",
                "trailing.Dot.",
                "segment.2StartsWithDigit",
                42
        );

        assertFalse(invalidNames.stream().anyMatch(TotalDebugConfig::isValidPacketClassName));
    }
}
