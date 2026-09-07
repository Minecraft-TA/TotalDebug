package com.github.minecraft_ta.totaldebug.network;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForwardedCompanionPayloadSinkTest {
    private static final ForwardedCompanionPayload PAYLOAD = new ForwardedCompanionPayload(
            ResourceLocation.fromNamespaceAndPath("total_debug", "test"),
            new byte[]{42}
    );

    @Test
    void deliversOnlyWhileAReceiverIsInstalled() throws Exception {
        ForwardedCompanionPayloadSink sink = new ForwardedCompanionPayloadSink();
        List<ForwardedCompanionPayload> received = new ArrayList<>();

        assertFalse(sink.deliver(PAYLOAD));
        try (AutoCloseable ignored = sink.install(received::add)) {
            assertTrue(sink.deliver(PAYLOAD));
        }
        assertFalse(sink.deliver(PAYLOAD));
        assertEquals(List.of(PAYLOAD), received);
    }

    @Test
    void rejectsCompetingReceivers() throws Exception {
        ForwardedCompanionPayloadSink sink = new ForwardedCompanionPayloadSink();

        try (AutoCloseable ignored = sink.install(payload -> {})) {
            assertThrows(IllegalStateException.class, () -> sink.install(payload -> {}));
        }
    }

    @Test
    void staleRegistrationCannotRemoveANewerReceiver() throws Exception {
        ForwardedCompanionPayloadSink sink = new ForwardedCompanionPayloadSink();
        AutoCloseable firstRegistration = sink.install(payload -> {});
        firstRegistration.close();
        List<ForwardedCompanionPayload> received = new ArrayList<>();

        try (AutoCloseable ignored = sink.install(received::add)) {
            firstRegistration.close();
            assertTrue(sink.deliver(PAYLOAD));
        }

        assertEquals(List.of(PAYLOAD), received);
    }
}
