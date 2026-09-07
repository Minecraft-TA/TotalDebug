package com.github.minecraft_ta.totalDebugCompanion.debugger;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalJvmDebugTargetResolverTest {
    @Test
    void parsesTheTemurinAgentProperty() throws Exception {
        assertEquals(50_321, LocalJvmDebugTargetResolver.parsePort("dt_socket:50321"));
        assertEquals(50_322, LocalJvmDebugTargetResolver.parsePort("dt_socket:127.0.0.1:50322"));
    }

    @Test
    void rejectsUnsupportedOrInvalidAgentProperties() {
        assertThrows(IOException.class, () -> LocalJvmDebugTargetResolver.parsePort("dt_shmem:debug"));
        assertThrows(IOException.class, () -> LocalJvmDebugTargetResolver.parsePort("dt_socket:0"));
        assertThrows(IOException.class, () -> LocalJvmDebugTargetResolver.parsePort("dt_socket:not-a-port"));
    }
}
