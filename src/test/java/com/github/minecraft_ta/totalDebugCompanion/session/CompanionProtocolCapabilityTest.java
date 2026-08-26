package com.github.minecraft_ta.totalDebugCompanion.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionProtocolCapabilityTest {
    @Test
    void supportsOptionalFeaturesWithoutMakingThemPartOfTheRequiredCore() {
        assertEquals(
                CompanionProtocol.CORE_CAPABILITIES
                        | CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION
                        | CompanionProtocol.CAPABILITY_DEBUGGER,
                CompanionProtocol.SUPPORTED_CAPABILITIES
        );
        assertEquals(0, CompanionProtocol.CORE_CAPABILITIES & CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION);
        assertEquals(0, CompanionProtocol.CORE_CAPABILITIES & CompanionProtocol.CAPABILITY_DEBUGGER);
    }
}
