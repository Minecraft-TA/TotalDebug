package com.github.minecraft_ta.totaldebug.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPolicyTest {
    private static ServerPolicy scripts(boolean enabled, boolean operatorOnly) {
        return new ServerPolicy(enabled, operatorOnly, "Server-side scripts are disabled by the server configuration",
                "You do not have permission to run server-side scripts");
    }

    @Test
    void rejectsScriptsWhenTheServerSettingIsDisabled() {
        ServerPolicy.Decision decision = scripts(false, false).evaluate(true);

        assertFalse(decision.allowed());
        assertEquals("Server-side scripts are disabled by the server configuration", decision.rejectionReason());
    }

    @Test
    void rejectsNonOperatorsWhenTheOperatorSettingIsEnabled() {
        ServerPolicy.Decision decision = scripts(true, true).evaluate(false);

        assertFalse(decision.allowed());
        assertEquals("You do not have permission to run server-side scripts", decision.rejectionReason());
    }

    @Test
    void acceptsAnOperatorOrAnExplicitlyPublicServer() {
        assertTrue(scripts(true, true).evaluate(true).allowed());
        assertTrue(scripts(true, false).evaluate(false).allowed());
    }
}
