package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.storage.CompanionLaunchContract;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerManifestMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionHandshakeConcurrencyTest {
    @TempDir
    Path temporaryDirectory;

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

}
