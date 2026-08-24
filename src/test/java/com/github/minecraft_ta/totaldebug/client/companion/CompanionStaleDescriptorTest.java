package com.github.minecraft_ta.totaldebug.client.companion;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionStaleDescriptorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void discardsAStaleDescriptorAfterItsPidWasReused() throws Exception {
        Path appHome = Files.createDirectories(this.temporaryDirectory.resolve("app-home"));
        Path descriptorFile = appHome.resolve(CompanionLaunchContract.INSTANCE_DESCRIPTOR_FILE_NAME);
        Path keyFile = appHome.resolve(CompanionLaunchContract.INSTANCE_KEY_FILE_NAME);
        Files.writeString(
                descriptorFile,
                "protocol=4\nport=41731\npid=" + ProcessHandle.current().pid() + "\n",
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
            assertFalse(Files.exists(descriptorFile));
            assertFalse(Files.exists(keyFile));
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
        Path descriptorFile = appHome.resolve(CompanionLaunchContract.INSTANCE_DESCRIPTOR_FILE_NAME);
        Path keyFile = appHome.resolve(CompanionLaunchContract.INSTANCE_KEY_FILE_NAME);
        Files.writeString(
                descriptorFile,
                "protocol=4\nport=41731\npid=" + ProcessHandle.current().pid() + "\n",
                StandardCharsets.UTF_8
        );
        Files.writeString(keyFile, "a".repeat(64), StandardCharsets.US_ASCII);
        Path lockFile = appHome.resolve(CompanionLaunchContract.INSTANCE_LOCK_FILE_NAME);
        Path totalDebugDirectory = Files.createDirectories(this.temporaryDirectory.resolve("locked-instance/totaldebug"));
        String previousHome = System.getProperty(CompanionLaunchContract.APP_HOME_PROPERTY);
        System.setProperty(CompanionLaunchContract.APP_HOME_PROPERTY, appHome.toString());
        try (FileChannel channel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        ); FileLock ignored = channel.lock();
             CompanionAppClient client = new CompanionAppClient(totalDebugDirectory)) {
            java.io.IOException failure = assertThrows(
                    java.io.IOException.class,
                    () -> invokeReadLiveDescriptor(client)
            );

            assertTrue(failure.getMessage().contains("Close the running Companion before using protocol 5"));
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
