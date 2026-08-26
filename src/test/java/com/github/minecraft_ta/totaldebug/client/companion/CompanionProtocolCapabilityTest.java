package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionProtocolCapabilityTest {
    @Test
    void requestsOptionalFeaturesWithoutMakingThemPartOfTheRequiredCore() {
        assertEquals(
                CompanionProtocol.CORE_CAPABILITIES
                        | CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION
                        | CompanionProtocol.CAPABILITY_DEBUGGER,
                CompanionProtocol.REQUESTED_CAPABILITIES
        );
        assertEquals(0, CompanionProtocol.CORE_CAPABILITIES & CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION);
        assertEquals(0, CompanionProtocol.CORE_CAPABILITIES & CompanionProtocol.CAPABILITY_DEBUGGER);
    }
}
