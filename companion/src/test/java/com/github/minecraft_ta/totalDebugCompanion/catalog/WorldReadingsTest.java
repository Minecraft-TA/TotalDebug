package com.github.minecraft_ta.totalDebugCompanion.catalog;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldReadingsTest {
    @Test
    void listenersHearOfAReadThatFoundSomethingElse() {
        WorldReadings readings = new WorldReadings();
        AtomicInteger changes = new AtomicInteger();
        Runnable remove = readings.addListener(changes::incrementAndGet);
        WorldReadings.Summary world = new WorldReadings.Summary(Path.of("saves/World"), 59, 15);

        readings.read(world);
        assertEquals(0, changes.get(), "the first read is what the tree shows");
        readings.read(world);
        assertEquals(0, changes.get(), "reading the same world again changes nothing");
        readings.read(new WorldReadings.Summary(Path.of("saves/World"), 59, 16));
        assertEquals(1, changes.get(), "a datapack was added since");
        readings.read(WorldReadings.Summary.NONE);
        assertEquals(2, changes.get(), "the world could not be read any more");

        remove.run();
        readings.read(world);
        assertEquals(2, changes.get());
    }
}
