package com.github.minecraft_ta.totaldebug.server.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerScriptPolicyTest {
    @Test
    void rejectsScriptsWhenTheServerSettingIsDisabled() {
        ServerScriptPolicy.Decision decision = new ServerScriptPolicy(false, false).evaluate(true);

        assertFalse(decision.allowed());
        assertEquals("Server-side scripts are disabled by the server configuration", decision.rejectionReason());
    }

    @Test
    void rejectsNonOperatorsWhenTheOperatorSettingIsEnabled() {
        ServerScriptPolicy.Decision decision = new ServerScriptPolicy(true, true).evaluate(false);

        assertFalse(decision.allowed());
        assertEquals("You do not have permission to run server-side scripts", decision.rejectionReason());
    }

    @Test
    void acceptsAnOperatorOrAnExplicitlyPublicServer() {
        assertTrue(new ServerScriptPolicy(true, true).evaluate(true).allowed());
        assertTrue(new ServerScriptPolicy(true, false).evaluate(false).allowed());
    }
}
