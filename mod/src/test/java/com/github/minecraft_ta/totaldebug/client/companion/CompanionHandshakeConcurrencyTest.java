package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.storage.CompanionLaunchContract;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerManifestMessage;
import java.util.List;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionHandshakeConcurrencyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void serverHelloHandlingDoesNotWaitForTheForegroundRequestMonitor() throws Exception {
        Path appHome = Files.createDirectories(this.temporaryDirectory.resolve("app-home"));
        Path totalDebugDirectory = Files.createDirectories(this.temporaryDirectory.resolve("instance/total-debug"));
        String previousHome = System.getProperty(CompanionLaunchContract.APP_HOME_PROPERTY);
        System.setProperty(CompanionLaunchContract.APP_HOME_PROPERTY, appHome.toString());
        try (CompanionAppClient client = new CompanionAppClient(totalDebugDirectory);
             var executor = Executors.newSingleThreadExecutor()) {
            ServerHelloMessage hello = acceptedServerHello();
            Method handler = CompanionAppClient.class.getDeclaredMethod("handleServerHello", ServerHelloMessage.class);
            handler.setAccessible(true);

            synchronized (client) {
                assertTimeoutPreemptively(
                        Duration.ofSeconds(2),
                        () -> executor.submit(() -> {
                            invoke(handler, client, hello);
                            return null;
                        }).get()
                );
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty(CompanionLaunchContract.APP_HOME_PROPERTY);
            } else {
                System.setProperty(CompanionLaunchContract.APP_HOME_PROPERTY, previousHome);
            }
        }
    }

    @Test
    void replayRetainsOnlyTheBaselineAndDisconnectClearsIt() throws Exception {
        Path appHome = Files.createDirectories(this.temporaryDirectory.resolve("app-home"));
        Path root = Files.createDirectories(this.temporaryDirectory.resolve("instance/total-debug"));
        String previousHome = System.getProperty(CompanionLaunchContract.APP_HOME_PROPERTY);
        System.setProperty(CompanionLaunchContract.APP_HOME_PROPERTY, appHome.toString());
        try (var client = new CompanionAppClient(root)) {
            var baseline = ServerManifestMessage.split("session", new byte[]{1, 2, 3}).getFirst();
            client.acceptServerManifest(baseline);
            for (var detail : ServerManifestMessage.split("session", "request", 0,
                    new byte[ServerManifestMessage.CHUNK_BYTES + 1])) client.acceptServerManifest(detail);
            var field = CompanionAppClient.class.getDeclaredField("serverManifest");
            field.setAccessible(true);
            assertEquals(List.of(baseline), field.get(client));
            var cleared = ServerManifestMessage.unavailable("Disconnected");
            client.acceptServerManifest(cleared);
            assertEquals(List.of(cleared), field.get(client));
        } finally {
            if (previousHome == null) System.clearProperty(CompanionLaunchContract.APP_HOME_PROPERTY);
            else System.setProperty(CompanionLaunchContract.APP_HOME_PROPERTY, previousHome);
        }
    }

    private static ServerHelloMessage acceptedServerHello() {
        ByteBuffer bytes = ByteBuffer.allocate(Integer.BYTES + 1 + Integer.BYTES)
                .putInt(CompanionProtocol.VERSION)
                .put((byte) 1)
                .putInt(0);
        bytes.flip();
        ServerHelloMessage message = new ServerHelloMessage();
        message.read(new ByteBufferInputStream(bytes));
        return message;
    }

    private static void invoke(Method handler, CompanionAppClient client, ServerHelloMessage hello) throws Exception {
        try {
            handler.invoke(client, hello);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception nested) {
                throw nested;
            }
            throw exception;
        }
    }
}
