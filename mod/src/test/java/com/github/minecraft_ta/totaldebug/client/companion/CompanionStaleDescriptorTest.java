package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.storage.CompanionLaunchContract;
import com.github.minecraft_ta.totaldebug.storage.AppPaths;
import com.github.minecraft_ta.totaldebug.storage.CompanionSessionDescriptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionStaleDescriptorTest {
    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"old-protocol", "dead-process", "malformed", "already-published", "first-launch"})
    void startupWaitsForAReplacementInsteadOfReadingTheStalePublication(String kind) throws Exception {
        Path appHome = Files.createDirectories(temporaryDirectory.resolve("replacement"));
        var paths = new AppPaths(appHome);
        Files.createDirectories(paths.run());
        String stale = switch (kind) {
            case "old-protocol" -> "protocol=1\nport=41731\npid=" + ProcessHandle.current().pid() + "\n";
            case "dead-process" -> "protocol=" + CompanionProtocol.VERSION + "\nport=41731\npid=9223372036854775807\nprojectPort=41732\n";
            default -> "incomplete descriptor";
        };
        byte[] previous = kind.equals("first-launch") ? null : stale.getBytes(StandardCharsets.UTF_8);
        if (previous != null) Files.write(paths.instanceDescriptor(), previous);
        var replacement = new CompanionSessionDescriptor(CompanionProtocol.VERSION, 41731, ProcessHandle.current().pid(), 41732, null);
        String previousHome = System.getProperty(CompanionLaunchContract.APP_HOME_PROPERTY);
        System.setProperty(CompanionLaunchContract.APP_HOME_PROPERTY, appHome.toString());
        var timeouts = new CompanionTimeouts(Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(10));
        try (var client = new CompanionAppClient(temporaryDirectory.resolve("game/total-debug"), timeouts);
             var worker = Executors.newSingleThreadExecutor();
             var channel = FileChannel.open(paths.instanceLock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            if (kind.equals("already-published")) replacement.writeAtomically(paths.instanceDescriptor());
            var waiting = new CountDownLatch(1);
            var result = worker.submit(() -> {
                waiting.countDown();
                return invokeAwaitDescriptor(client, paths.instanceDescriptor(), previous);
            });
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            if (!kind.equals("already-published")) {
                assertThrows(TimeoutException.class, () -> result.get(150, TimeUnit.MILLISECONDS));
                if (previous != null) assertEquals(stale, Files.readString(paths.instanceDescriptor()), "Startup must not delete another process's publication");
                replacement.writeAtomically(paths.instanceDescriptor());
            }
            assertEquals(replacement, result.get(2, TimeUnit.SECONDS));
        } finally {
            if (previousHome == null) System.clearProperty(CompanionLaunchContract.APP_HOME_PROPERTY);
            else System.setProperty(CompanionLaunchContract.APP_HOME_PROPERTY, previousHome);
        }
    }

    private static Object invokeAwaitDescriptor(CompanionAppClient client, Path file, byte[] previous) throws Exception {
        Method method = CompanionAppClient.class.getDeclaredMethod("awaitDescriptor", Path.class, byte[].class);
        method.setAccessible(true);
        try { return method.invoke(client, file, previous); }
        catch (InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }

    @Test
    void ignoresAStaleDescriptorWithoutDeletingCompanionOwnedFiles() throws Exception {
        Path appHome = Files.createDirectories(this.temporaryDirectory.resolve("app-home"));
        var paths = new AppPaths(appHome);
        Files.createDirectories(paths.run());
        Path descriptorFile = paths.instanceDescriptor();
        Path keyFile = paths.instanceKey();
        Files.writeString(
                descriptorFile,
                "protocol=14\nport=41731\npid=" + ProcessHandle.current().pid() + "\n",
                StandardCharsets.UTF_8
        );
        Files.writeString(keyFile, "a".repeat(64), StandardCharsets.US_ASCII);
        Path totalDebugDirectory = Files.createDirectories(this.temporaryDirectory.resolve("instance/totaldebug"));
        String previousHome = System.getProperty(CompanionLaunchContract.APP_HOME_PROPERTY);
        System.setProperty(CompanionLaunchContract.APP_HOME_PROPERTY, appHome.toString());
        try (CompanionAppClient client = new CompanionAppClient(totalDebugDirectory)) {
            Method readLiveDescriptor = CompanionAppClient.class.getDeclaredMethod("readLiveDescriptor");
            readLiveDescriptor.setAccessible(true);
            Object descriptor;
            try {
                descriptor = readLiveDescriptor.invoke(client);
            } catch (InvocationTargetException exception) {
                throw (Exception) exception.getCause();
            }

            assertNull(descriptor);
            assertTrue(Files.exists(descriptorFile));
            assertTrue(Files.exists(keyFile));
        } finally {
            if (previousHome == null) {
                System.clearProperty(CompanionLaunchContract.APP_HOME_PROPERTY);
            } else {
                System.setProperty(CompanionLaunchContract.APP_HOME_PROPERTY, previousHome);
            }
        }
    }

    @Test
    void keepsRejectingARealRunningCompanionWithAnotherProtocol() throws Exception {
        Path appHome = Files.createDirectories(this.temporaryDirectory.resolve("locked-app-home"));
        var paths = new AppPaths(appHome);
        Files.createDirectories(paths.run());
        Path descriptorFile = paths.instanceDescriptor();
        Path keyFile = paths.instanceKey();
        Files.writeString(
                descriptorFile,
                "protocol=14\nport=41731\npid=" + ProcessHandle.current().pid() + "\n",
                StandardCharsets.UTF_8
        );
        Files.writeString(keyFile, "a".repeat(64), StandardCharsets.US_ASCII);
        Path lockFile = paths.instanceLock();
        Path totalDebugDirectory = Files.createDirectories(this.temporaryDirectory.resolve("locked-instance/totaldebug"));
        String previousHome = System.getProperty(CompanionLaunchContract.APP_HOME_PROPERTY);
        System.setProperty(CompanionLaunchContract.APP_HOME_PROPERTY, appHome.toString());
        try (FileChannel channel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        ); FileLock ignored = channel.lock();
             CompanionAppClient client = new CompanionAppClient(totalDebugDirectory)) {
            IOException failure = assertThrows(
                    IOException.class,
                    () -> invokeReadLiveDescriptor(client)
            );

            assertTrue(failure.getMessage().contains("Close the running Companion before using protocol " + CompanionProtocol.VERSION));
            assertTrue(Files.isRegularFile(descriptorFile));
            assertTrue(Files.isRegularFile(keyFile));
        } finally {
            if (previousHome == null) {
                System.clearProperty(CompanionLaunchContract.APP_HOME_PROPERTY);
            } else {
                System.setProperty(CompanionLaunchContract.APP_HOME_PROPERTY, previousHome);
            }
        }
    }

    private static Object invokeReadLiveDescriptor(CompanionAppClient client) throws Exception {
        Method readLiveDescriptor = CompanionAppClient.class.getDeclaredMethod("readLiveDescriptor");
        readLiveDescriptor.setAccessible(true);
        try {
            return readLiveDescriptor.invoke(client);
        } catch (InvocationTargetException exception) {
            throw (Exception) exception.getCause();
        }
    }
}
