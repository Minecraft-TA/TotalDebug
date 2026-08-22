package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionProtocolCapabilityTest {
    @Test
    void requestsScriptsWithoutMakingThemPartOfTheRequiredCore() {
        assertEquals(
                CompanionProtocol.CORE_CAPABILITIES | CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION,
                CompanionProtocol.REQUESTED_CAPABILITIES
        );
        assertEquals(0, CompanionProtocol.CORE_CAPABILITIES & CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION);
    }
}
