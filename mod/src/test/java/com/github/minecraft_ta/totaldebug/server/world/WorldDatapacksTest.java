package com.github.minecraft_ta.totaldebug.server.world;

import com.github.minecraft_ta.totaldebug.server.ServerPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldDatapacksTest {
    private static ServerPolicy worldChanges(boolean enabled, boolean operatorOnly) {
        return new ServerPolicy(enabled, operatorOnly, "Changing this server's world from Companion is disabled by the server configuration",
                "You need operator permission on this server to change its world");
    }

    @Test
    void theOwnerOfASingleplayerWorldMayAlwaysChangeIt() {
        assertTrue(WorldDatapacks.decision(true, false, null).allowed(), "open to LAN or not, whatever the configuration says");
    }

    @Test
    void anyoneElseNeedsWhatTheServersConfigurationSets() {
        assertTrue(WorldDatapacks.decision(false, true, worldChanges(true, true)).allowed(), "an operator, by default");
        assertEquals("You need operator permission on this server to change its world",
                WorldDatapacks.decision(false, false, worldChanges(true, true)).rejectionReason());
        assertTrue(WorldDatapacks.decision(false, false, worldChanges(true, false)).allowed(), "a server open to every player");
        assertEquals("Changing this server's world from Companion is disabled by the server configuration",
                WorldDatapacks.decision(false, true, worldChanges(false, true)).rejectionReason());
    }
}
