package com.github.minecraft_ta.totalDebugCompanion.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A value read from a file others write, read again when the user comes back to Companion or its owner asks. */
class FileReadingTest {
    @TempDir Path directory;

    @Test
    void aValueIsReadWhenFirstAskedForAndToldOnlyWhenItChanged() throws Exception {
        Path file = Files.writeString(this.directory.resolve("options.txt"), "one");
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger told = new AtomicInteger();
        try (FileReading<String> reading = new FileReading<>(() -> {
            reads.incrementAndGet();
            return Files.readString(file);
        })) {
            reading.changed().subscribe(told::incrementAndGet);
            settle(reading);
            assertEquals(0, reads.get(), "nobody asked for the value yet");
            assertEquals(Optional.empty(), reading.published());

            assertEquals("one", reading.value());
            assertEquals("one", reading.value());
            assertEquals(1, reads.get(), "a value read is kept");
            assertEquals(0, told.get(), "who asked for the first value has it");

            reading.refresh();
            settle(reading);
            assertEquals(0, told.get(), "read again unchanged, nothing is told");

            Files.writeString(file, "two");
            reading.refresh();
            await(() -> told.get() == 1);
            assertEquals("two", reading.value());
            assertEquals(Optional.of("two"), reading.published());
        }
    }

    @Test
    void aRefreshBeforeAnyoneAskedPublishesTheFirstValueAndTellsIt() throws Exception {
        Path file = Files.writeString(this.directory.resolve("level.dat"), "World");
        AtomicInteger told = new AtomicInteger();
        try (FileReading<String> reading = new FileReading<>(() -> Files.readString(file))) {
            // As a tab's title, which shows the value without asking for it.
            reading.changed().subscribe(told::incrementAndGet);
            reading.refresh();
            await(() -> told.get() == 1);
            assertEquals(Optional.of("World"), reading.published());
        }
    }

    @Test
    void comingBackToCompanionReadsAgainUntilTheReadingCloses() throws Exception {
        Path file = Files.writeString(this.directory.resolve("options.txt"), "one");
        AtomicInteger told = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        FileReading<String> reading = new FileReading<>(() -> {
            reads.incrementAndGet();
            return Files.readString(file);
        });
        reading.changed().subscribe(told::incrementAndGet);
        WindowFocus.returned().fire();
        settle(reading);
        assertEquals(0, reads.get(), "coming back reads no value nobody asked for yet");
        reading.value();

        // The game saved the file while the user was in it.
        Files.writeString(file, "two");
        WindowFocus.returned().fire();
        await(() -> told.get() == 1);

        reading.close();
        Files.writeString(file, "three");
        WindowFocus.returned().fire();
        // As a key change that completes after its project closed.
        reading.refresh();
        settle(reading);
        assertEquals(1, told.get(), "a closed reading reads no more");
        assertEquals("two", reading.value());
    }

    @Test
    void aReadUnderWayWhenTheReadingClosesPublishesNothing() throws Exception {
        Path file = Files.writeString(this.directory.resolve("options.txt"), "one");
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger told = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        FileReading<String> reading = new FileReading<>(() -> {
            if (reads.incrementAndGet() == 2) {
                paused.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    throw new IOException(interrupted);
                }
            }
            return Files.readString(file);
        });
        reading.changed().subscribe(told::incrementAndGet);
        reading.value();

        Files.writeString(file, "two");
        reading.refresh();
        assertTrue(paused.await(5, TimeUnit.SECONDS));
        // The project closes while the read is under way.
        reading.close();
        release.countDown();
        settle(reading);
        assertEquals(0, told.get());
        assertEquals(Optional.of("one"), reading.published());
    }

    @Test
    void aFailedReadKeepsTheValueAndTheNextOneThatSucceedsIsTold() throws Exception {
        Path file = Files.writeString(this.directory.resolve("options.txt"), "one");
        AtomicInteger told = new AtomicInteger();
        boolean[] failing = {false};
        try (FileReading<String> reading = new FileReading<>(() -> {
            if (failing[0]) throw new IOException("being written");
            return Files.readString(file);
        })) {
            reading.changed().subscribe(told::incrementAndGet);
            reading.value();

            failing[0] = true;
            reading.refresh();
            settle(reading);
            assertEquals("one", reading.value(), "the value read before stays");

            // The file holds what it held: a page that showed the failure still hears of the read.
            failing[0] = false;
            reading.refresh();
            await(() -> told.get() == 1);
        }
    }

    @Test
    void aFirstReadThatFailsIsTriedAgainWhenAskedOrRefreshed() throws Exception {
        Path file = this.directory.resolve("options.txt");
        AtomicInteger told = new AtomicInteger();
        try (FileReading<String> reading = new FileReading<>(() -> Files.readString(file, StandardCharsets.UTF_8))) {
            reading.changed().subscribe(told::incrementAndGet);
            assertThrows(IOException.class, reading::value);

            Files.writeString(file, "one");
            reading.refresh();
            await(() -> told.get() == 1);
            assertEquals("one", reading.value());
        }
    }

    @Test
    void readsRunOneAtATimeInOrder() throws Exception {
        Path file = Files.writeString(this.directory.resolve("options.txt"), "one");
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger overlapped = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        try (FileReading<String> reading = new FileReading<>(() -> {
            if (running.incrementAndGet() > 1) overlapped.incrementAndGet();
            try {
                if (reads.incrementAndGet() == 2) {
                    paused.countDown();
                    release.await();
                }
                return Files.readString(file);
            } catch (InterruptedException interrupted) {
                throw new IOException(interrupted);
            } finally {
                running.decrementAndGet();
            }
        })) {
            reading.value();
            // A read paused mid-flight while the file changes and another read is asked for.
            reading.refresh();
            assertTrue(paused.await(5, TimeUnit.SECONDS));
            Files.writeString(file, "two");
            reading.refresh();
            Thread.sleep(100);
            assertEquals(2, reads.get(), "the second read waits for the first");
            release.countDown();
            await(() -> reading.published().orElse("").equals("two"));
            assertEquals(0, overlapped.get());
        }
    }

    /** Gives the reads asked for so far time to run, for a test that nothing more happens. */
    private static void settle(FileReading<String> reading) throws InterruptedException {
        Thread.sleep(200);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "timed out");
    }
}
