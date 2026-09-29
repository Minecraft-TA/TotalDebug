package com.github.minecraft_ta.totaldebug.client.catalog;

import com.github.minecraft_ta.totaldebug.protocol.message.PreparedFilePayload;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Timeout(10)
class PackCatalogPublisherTest {
    @TempDir Path directory;
    private final BlockingQueue<PreparedFilePayload> sent = new LinkedBlockingQueue<>();
    private final AtomicInteger captures = new AtomicInteger();
    private final AtomicReference<String> language = new AtomicReference<>("en_us");
    private volatile CompletableFuture<PackCatalog> capture = new CompletableFuture<>();
    private PackCatalogPublisher publisher;

    @AfterEach
    void close() {
        if (this.publisher != null) this.publisher.close();
    }

    @Test
    void capturesWritesAndAnnouncesTheCatalog() throws Exception {
        Path file = this.directory.resolve("catalog.json");
        PackCatalogPublisher publisher = publisher(file);

        publisher.request("inventory", Map.of("mekanism", "mekanism"));
        assertEquals(PreparedFilePayload.State.PREPARING, next().state());
        this.capture.complete(catalog("inventory", "en_us"));

        PreparedFilePayload available = next();
        assertEquals(PreparedFilePayload.Kind.PACK_CATALOG, available.kind());
        assertEquals(PreparedFilePayload.State.READY, available.state());
        assertEquals("inventory", available.inventoryId());
        assertEquals(file.toString(), available.file());
        assertEquals(catalog("inventory", "en_us"), PackCatalog.read(file));

        publisher.request("inventory", Map.of());
        assertNull(this.sent.poll(100, TimeUnit.MILLISECONDS), "the catalog told already is not told again");
        awaitCaptures(1);
    }

    @Test
    void reusesAMatchingCatalogAndRecapturesForAnotherInventoryOrLanguage() throws Exception {
        Path file = this.directory.resolve("catalog.json");
        catalog("inventory", "en_us").write(file);

        publisher(file).request("inventory", Map.of());
        assertEquals(PreparedFilePayload.State.READY, next().state(), "the saved catalog is told at once");
        awaitCaptures(1);
        this.capture.complete(catalog("inventory", "en_us"));
        assertNull(this.sent.poll(200, TimeUnit.MILLISECONDS), "checked quietly, and the same catalog is not told again");
        this.capture = new CompletableFuture<>();

        this.language.set("de_de");
        this.publisher.request("other", Map.of());
        assertEquals(PreparedFilePayload.State.PREPARING, next().state());
        awaitCaptures(2);
    }

    @Test
    void aSavedCatalogIsToldAndReplacedWhenThePacksChangedItsNamesSince() throws Exception {
        Path file = this.directory.resolve("catalog.json");
        catalog("inventory", "en_us").write(file);
        publisher(file).request("inventory", Map.of());
        assertEquals(PreparedFilePayload.State.READY, next().state());
        awaitCaptures(1);
        this.capture.complete(catalog("inventory", "en_us", "Smooth Stone"));
        assertEquals(PreparedFilePayload.State.READY, next().state(), "a pack enabled since renamed an entry");
        assertEquals(catalog("inventory", "en_us", "Smooth Stone"), PackCatalog.read(file));
    }

    @Test
    void reloadedResourcesCaptureAgainQuietlyAndTellOnlyACatalogThatDiffers() throws Exception {
        Path file = this.directory.resolve("catalog.json");
        catalog("inventory", "en_us").write(file);
        PackCatalogPublisher publisher = publisher(file);
        publisher.recapture();
        publisher.request("inventory", Map.of());
        assertEquals(PreparedFilePayload.State.READY, next().state(), "before any request, the saved catalog is still reused");
        awaitCaptures(1);
        this.capture.complete(catalog("inventory", "en_us"));
        assertNull(this.sent.poll(200, TimeUnit.MILLISECONDS));

        this.capture = new CompletableFuture<>();
        publisher.recapture();
        publisher.request("inventory", Map.of());
        awaitCaptures(2);
        this.capture.complete(catalog("inventory", "en_us"));
        assertNull(this.sent.poll(200, TimeUnit.MILLISECONDS),
                "Companion keeps the catalog it has: no capture is told, and the same catalog is not told again");

        this.capture = new CompletableFuture<>();
        publisher.recapture();
        publisher.request("inventory", Map.of());
        awaitCaptures(3);
        PackCatalog renamed = catalog("inventory", "en_us", "Smooth Stone");
        this.capture.complete(renamed);
        assertEquals(PreparedFilePayload.State.READY, next().state(), "a pack renamed an entry: the new catalog is told");
        assertEquals(renamed, PackCatalog.read(file));
    }

    @Test
    void aQuietCaptureThatFailsKeepsTheCatalogCompanionHas() throws Exception {
        Path file = this.directory.resolve("catalog.json");
        catalog("inventory", "en_us").write(file);
        PackCatalogPublisher publisher = publisher(file);
        publisher.request("inventory", Map.of());
        assertEquals(PreparedFilePayload.State.READY, next().state());
        awaitCaptures(1);
        this.capture.complete(catalog("inventory", "en_us"));

        this.capture = new CompletableFuture<>();
        publisher.recapture();
        publisher.request("inventory", Map.of());
        awaitCaptures(2);
        this.capture.completeExceptionally(new IllegalStateException("registry exploded"));
        assertNull(this.sent.poll(200, TimeUnit.MILLISECONDS), "the failure is logged; Companion keeps its catalog");
        assertEquals(catalog("inventory", "en_us"), PackCatalog.read(file));

        this.capture = new CompletableFuture<>();
        publisher.request("inventory", Map.of());
        awaitCaptures(3);
        this.capture.complete(catalog("inventory", "en_us", "Smooth Stone"));
        assertEquals(PreparedFilePayload.State.READY, next().state(), "the next request, such as a reconnect, tries again");
    }

    @Test
    void reportsAFailedCaptureAndRetriesOnTheNextRequest() throws Exception {
        PackCatalogPublisher publisher = publisher(this.directory.resolve("catalog.json"));

        publisher.request("inventory", Map.of());
        assertEquals(PreparedFilePayload.State.PREPARING, next().state());
        this.capture.completeExceptionally(new IllegalStateException("registry exploded"));
        PreparedFilePayload failed = next();
        assertEquals(PreparedFilePayload.State.FAILED, failed.state());
        assertEquals("registry exploded", failed.detail());

        this.capture = new CompletableFuture<>();
        publisher.request("inventory", Map.of());
        assertEquals(PreparedFilePayload.State.PREPARING, next().state());
        awaitCaptures(2);
        assertNull(this.sent.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void aLanguageChangeCapturesAgainForTheSameInventory() throws Exception {
        PackCatalogPublisher publisher = publisher(this.directory.resolve("catalog.json"));
        publisher.request("inventory", Map.of());
        assertEquals(PreparedFilePayload.State.PREPARING, next().state());
        this.capture.complete(catalog("inventory", "en_us"));
        assertEquals(PreparedFilePayload.State.READY, next().state());

        this.language.set("de_de");
        this.capture = new CompletableFuture<>();
        publisher.request("inventory", Map.of());
        awaitCaptures(2);
        this.capture.complete(catalog("inventory", "de_de"));

        assertEquals(PreparedFilePayload.State.READY, next().state(), "the names in the other language are told, quietly captured");
        assertEquals("de_de", PackCatalog.readHeader(file()).language());
    }

    @Test
    void aSupersededCaptureNeitherWritesNorAnnounces() throws Exception {
        Path file = this.directory.resolve("catalog.json");
        PackCatalogPublisher publisher = publisher(file);
        publisher.request("old", Map.of());
        assertEquals(PreparedFilePayload.State.PREPARING, next().state());
        awaitCaptures(1);
        CompletableFuture<PackCatalog> superseded = this.capture;
        this.capture = new CompletableFuture<>();
        publisher.request("new", Map.of());
        assertEquals(PreparedFilePayload.State.PREPARING, next().state());
        awaitCaptures(2);

        superseded.complete(catalog("old", "en_us"));
        this.capture.complete(catalog("new", "en_us"));

        PreparedFilePayload available = next();
        assertEquals("new", available.inventoryId());
        assertNull(this.sent.poll(100, TimeUnit.MILLISECONDS));
        assertEquals("new", PackCatalog.readHeader(file).inventoryId());
    }

    private PackCatalogPublisher publisher(Path file) {
        this.publisher = new PackCatalogPublisher(file, this.language::get, (inventoryId, language, modules) -> {
            // Take the future before counting, so a test that saw the count can replace the future for the next call.
            CompletableFuture<PackCatalog> capture = this.capture;
            this.captures.incrementAndGet();
            return capture;
        }, this.sent::add);
        return this.publisher;
    }

    /** Waits for the capture count: the publisher announces a capture just before it starts one. */
    private void awaitCaptures(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (this.captures.get() < expected && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(expected, this.captures.get());
    }

    private PreparedFilePayload next() throws InterruptedException {
        return this.sent.poll(5, TimeUnit.SECONDS);
    }

    private Path file() {
        return this.directory.resolve("catalog.json");
    }

    private static PackCatalog catalog(String inventoryId, String language) {
        return catalog(inventoryId, language, "Stone");
    }

    private static PackCatalog catalog(String inventoryId, String language, String stoneName) {
        return new PackCatalog(inventoryId, language, List.of(),
                List.of(new PackCatalog.Registry("minecraft:item", List.of(new PackCatalog.RegistryEntry("minecraft:stone",
                        stoneName, "net.minecraft.world.item.BlockItem", "minecraft:stone", List.of(), Map.of())))),
                Map.of(), List.of(), List.of(), Map.of());
    }
}
