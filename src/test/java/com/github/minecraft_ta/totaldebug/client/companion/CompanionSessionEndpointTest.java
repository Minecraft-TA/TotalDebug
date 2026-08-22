package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionSessionEndpointTest {
    @Test
    void alwaysConnectsToTheExplicitIpv4LoopbackAddress() {
        InetSocketAddress address = CompanionAppClient.sessionAddress(41_731);

        assertArrayEquals(new byte[]{127, 0, 0, 1}, address.getAddress().getAddress());
        assertEquals(41_731, address.getPort());
    }
}
