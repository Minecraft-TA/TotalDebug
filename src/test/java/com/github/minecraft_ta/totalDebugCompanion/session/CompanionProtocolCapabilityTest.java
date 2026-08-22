package com.github.minecraft_ta.totalDebugCompanion.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionProtocolCapabilityTest {
    @Test
    void supportsScriptsWithoutMakingThemPartOfTheRequiredCore() {
        assertEquals(
                CompanionProtocol.CORE_CAPABILITIES | CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION,
                CompanionProtocol.SUPPORTED_CAPABILITIES
        );
        assertEquals(0, CompanionProtocol.CORE_CAPABILITIES & CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION);
    }
}
