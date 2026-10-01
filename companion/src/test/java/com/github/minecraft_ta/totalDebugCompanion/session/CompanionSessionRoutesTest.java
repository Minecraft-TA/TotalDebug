package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ProtocolBindings;
import com.github.minecraft_ta.totaldebug.protocol.message.ClientPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.PackStackMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReadyMessage;
import com.github.minecraft_ta.totaldebug.storage.CompanionSessionDescriptor;
import com.github.tth05.scnet.Client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The game's messages reach the routes registered for them, and a removed route no longer, even mid-delivery. */
class CompanionSessionRoutesTest {
    private static final String TOKEN = "correct-token-value-1234567890abcdef";

    @TempDir Path directory;

    @Test
    void aRouteRemovedWhileAMessageIsHandedOutDoesNotTakeIt() throws Exception {
        CompanionLaunchConfiguration configuration = new CompanionLaunchConfiguration(this.directory);
        Client game = new Client();
        try (CompanionSession session = new CompanionSession(TOKEN)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger late = new AtomicInteger();
            session.on(PackStackMessage.class, message -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            Runnable removed = session.on(PackStackMessage.class, message -> late.incrementAndGet());
            session.bindAndPublish(configuration);

            ProtocolBindings.registerMod(game.getMessageProcessor());
            CompletableFuture<Void> ready = new CompletableFuture<>();
            game.getMessageBus().listenAlways(ReadyMessage.class, message -> ready.complete(null));
            int port = CompanionSessionDescriptor.read(configuration.descriptorFile(), CompanionProtocol.VERSION).port();
            assertTrue(game.connect(CompanionSession.sessionAddress(port)));
            game.getMessageProcessor().enqueueMessage(new ClientHelloMessage(CompanionProtocol.VERSION, TOKEN, "profile",
                    this.directory.toString(), this.directory.toString()));
            ready.get(5, TimeUnit.SECONDS);

            game.getMessageProcessor().enqueueMessage(new PackStackMessage(new ClientPacksPayload(
                    new PackStackPayload(34, List.of(new PackStackPayload.Pack("file/Faithful", "Faithful", ""))), 48)));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the first route takes the message");
            // As the project the second route belongs to stops being the current one while the message is handed out.
            removed.run();
            release.countDown();
            Thread.sleep(200);
            assertEquals(0, late.get(), "a route removed meanwhile does not take it");
        } finally {
            game.close();
        }
    }
}
