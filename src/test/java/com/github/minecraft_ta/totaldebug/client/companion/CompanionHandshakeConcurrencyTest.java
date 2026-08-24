package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.client.companion.message.ServerHelloMessage;
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

    private static ServerHelloMessage acceptedServerHello() {
        ByteBuffer bytes = ByteBuffer.allocate(Integer.BYTES + 1 + Long.BYTES + Integer.BYTES)
                .putInt(CompanionProtocol.VERSION)
                .put((byte) 1)
                .putLong(CompanionProtocol.REQUESTED_CAPABILITIES)
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
