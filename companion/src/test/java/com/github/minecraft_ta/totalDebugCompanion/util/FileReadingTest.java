package com.github.minecraft_ta.totalDebugCompanion.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A file others write, read by its one owner on one path (docs/SYSTEMS.md, sections 1 and 2). */
class FileReadingTest {
    private static final Duration SETTLE = Duration.ofMillis(200);

    @TempDir Path directory;

    @Test
    void aWriteInPartsIsReadOnceItSettledAndAnEqualOneTellsNothing() throws Exception {
        Path file = write(this.directory.resolve("options.txt"), "a");
        AtomicInteger told = new AtomicInteger();
        try (FileReading<String> reading = new FileReading<>(file, FileReadingTest::text, SETTLE)) {
            reading.changed().subscribe(told::incrementAndGet);
            assertEquals("a", reading.value());

            write(file, "b");
            write(file, "bc");
            write(file, "bcd");
            await(() -> told.get() == 1);
            Thread.sleep(400);
            assertEquals(1, told.get(), "a write in parts is read once, when it settled");
            assertEquals("bcd", reading.value());

            write(file, "bcd");
            Thread.sleep(600);
            assertEquals(1, told.get(), "a write that leaves the value as it was tells nobody");
        }
    }

    @Test
    void aValueAskedBeforeTheOwnersFirstReadIsWhatTheNextChangeIsToldAgainst() throws Exception {
        Path file = write(this.directory.resolve("options.txt"), "a");
        AtomicInteger told = new AtomicInteger();
        try (FileReading<String> reading = new FileReading<>(file, FileReadingTest::text, SETTLE)) {
            reading.changed().subscribe(told::incrementAndGet);
            // A page opened at once asks, and the game saves right after.
            assertEquals("a", reading.value());
            write(file, "b");
            await(() -> told.get() == 1);
            assertEquals("b", reading.value());
        }
    }

    @Test
    void aFolderThatAppearsLaterIsFollowedOnceItDoes() throws Exception {
        Path file = this.directory.resolve("game/sub/options.txt");
        AtomicInteger told = new AtomicInteger();
        try (FileReading<String> reading = new FileReading<>(file, FileReadingTest::text, SETTLE)) {
            reading.changed().subscribe(told::incrementAndGet);
            assertEquals("", reading.value(), "a file that is not there yet holds nothing");

            // The game runs for the first time.
            write(file, "a");
            await(() -> told.get() == 1);
            write(file, "b");
            await(() -> told.get() == 2);
            assertEquals("b", reading.value());
        }
    }

    @Test
    void foldersMadeAtOnceAreFollowedDownToTheFile() throws Exception {
        for (int round = 0; round < 5; round++) {
            Path file = this.directory.resolve("round" + round + "/game/a/b/options.txt");
            AtomicInteger told = new AtomicInteger();
            try (FileReading<String> reading = new FileReading<>(file, FileReadingTest::text, SETTLE)) {
                reading.changed().subscribe(told::incrementAndGet);
                assertEquals("", reading.value());
                // As a game makes its folders in one go on its first start, while the watch moves down to them.
                write(file, "a");
                await(() -> told.get() == 1);
                write(file, "b");
                await(() -> told.get() == 2);
            }
        }
    }

    @Test
    void aFailedReadIsTriedAgainAndToldWhenItSucceeds() throws Exception {
        Path file = write(this.directory.resolve("options.txt"), "a");
        AtomicInteger failures = new AtomicInteger(0);
        AtomicInteger told = new AtomicInteger();
        try (FileReading<String> reading = new FileReading<>(file, path -> {
            if (failures.getAndDecrement() > 0) throw new IOException("written in parts");
            return text(path);
        }, SETTLE)) {
            reading.changed().subscribe(told::incrementAndGet);
            assertEquals("a", reading.value());

            failures.set(1);
            write(file, "b");
            await(() -> told.get() == 1);
            assertEquals("b", reading.value(), "the read tried again a second later finds the file whole");
        }
    }

    @Test
    void readsNeverRunAtOnce() throws Exception {
        Path file = write(this.directory.resolve("options.txt"), "a");
        AtomicInteger running = new AtomicInteger();
        AtomicInteger overlapping = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        try (FileReading<String> reading = new FileReading<>(file, path -> {
            if (running.incrementAndGet() > 1) overlapping.incrementAndGet();
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            reads.incrementAndGet();
            running.decrementAndGet();
            return text(path);
        }, SETTLE)) {
            reading.value();
            for (int ask = 0; ask < 20; ask++) {
                reading.readNow();
                write(file, "v" + ask);
            }
            // Reads a later request overtook are left out; the last write is read in the end.
            await(() -> "v19".equals(value(reading)));
            Thread.sleep(500);
            assertEquals(0, overlapping.get(), "the owner reads on one path, whoever asks");
        }
    }

    @Test
    void aReadingMovedToAnotherFileReadsItAndTells() throws Exception {
        Path first = write(this.directory.resolve("first/level.dat"), "first");
        Path second = write(this.directory.resolve("second/level.dat"), "second");
        AtomicInteger told = new AtomicInteger();
        try (FileReading<String> reading = new FileReading<>(first, FileReadingTest::text, SETTLE)) {
            reading.changed().subscribe(told::incrementAndGet);
            assertEquals("first", reading.value());

            // The game opened another world.
            reading.moveTo(second);
            await(() -> told.get() == 1);
            assertEquals("second", reading.value());
            write(first, "first again");
            Thread.sleep(600);
            assertEquals(1, told.get(), "the file followed before tells nothing any more");
        }
    }

    @Test
    void aClosedReadingTellsNothing() throws Exception {
        Path file = write(this.directory.resolve("options.txt"), "a");
        AtomicInteger told = new AtomicInteger();
        FileReading<String> reading = new FileReading<>(file, FileReadingTest::text, SETTLE);
        reading.changed().subscribe(told::incrementAndGet);
        assertEquals("a", reading.value());
        reading.close();
        write(file, "b");
        Thread.sleep(600);
        assertEquals(0, told.get());
    }

    @Test
    void aReadOvertakenByAnotherWritePublishesNothing() throws Exception {
        Path file = write(this.directory.resolve("options.txt"), "a");
        AtomicReference<CountDownLatch> gate = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        List<String> told = new CopyOnWriteArrayList<>();
        try (FileReading<String> reading = new FileReading<>(file, path -> {
            String read = text(path);
            CountDownLatch waiting = gate.get();
            if (waiting != null) {
                entered.countDown();
                try {
                    waiting.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return read;
        }, SETTLE)) {
            assertEquals("a", reading.value());
            reading.changed().subscribe(() -> told.add(value(reading)));

            // The game writes in parts: the first part is read, and the next part comes while that read runs.
            CountDownLatch release = new CountDownLatch(1);
            gate.set(release);
            write(file, "partial");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            gate.set(null);
            write(file, "partial and whole");
            Thread.sleep(100);
            release.countDown();
            await(() -> !told.isEmpty());
            Thread.sleep(500);
            assertEquals(List.of("partial and whole"), told, "the read the next write overtook is not told");
        }
    }

    @Test
    void aReadingClosedWhileItReadsTellsNothing() throws Exception {
        Path file = write(this.directory.resolve("options.txt"), "a");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean waitNext = new AtomicBoolean();
        AtomicInteger told = new AtomicInteger();
        FileReading<String> reading = new FileReading<>(file, path -> {
            String read = text(path);
            if (waitNext.getAndSet(false)) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return read;
        }, SETTLE);
        assertEquals("a", reading.value());
        reading.changed().subscribe(told::incrementAndGet);
        waitNext.set(true);
        write(file, "b");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        // The project closes while its reading reads.
        reading.close();
        release.countDown();
        Thread.sleep(500);
        assertEquals(0, told.get(), "a reading closed while it read tells nothing");
    }

    @Test
    void aFolderReachedThroughALinkIsFollowedByEveryReadingOfIt() throws Exception {
        Path folder = Files.createDirectories(this.directory.resolve("game"));
        Path link;
        try {
            link = Files.createSymbolicLink(this.directory.resolve("linked"), folder);
        } catch (UnsupportedOperationException | IOException | SecurityException notAllowed) {
            // Windows without the privilege to create links: nothing to check here.
            return;
        }
        Path file = write(folder.resolve("options.txt"), "a");
        AtomicInteger direct = new AtomicInteger();
        AtomicInteger linked = new AtomicInteger();
        try (FileReading<String> first = new FileReading<>(file, FileReadingTest::text, SETTLE);
             FileReading<String> second = new FileReading<>(link.resolve("options.txt"), FileReadingTest::text, SETTLE)) {
            first.changed().subscribe(direct::incrementAndGet);
            second.changed().subscribe(linked::incrementAndGet);
            first.value();
            second.value();
            write(file, "b");
            await(() -> direct.get() == 1 && linked.get() == 1);
            second.close();
            write(file, "c");
            await(() -> direct.get() == 2);
        }
    }

    private static String value(FileReading<String> reading) {
        try {
            return reading.value();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String text(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "timed out");
    }
}
