package com.github.minecraft_ta.totalDebugCompanion.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PrismInstancesTest {
    @TempDir Path root;

    @Test void readsVersionsAndLocalArtwork() throws Exception {
        Path pack = Files.createDirectories(root.resolve("instances/My Pack/minecraft")).getParent();
        Path icon = Files.createDirectories(root.resolve("icons")).resolve("pack.png");
        Files.write(icon, new byte[0]);
        Files.writeString(pack.resolve("instance.cfg"), "[General]\niconKey=pack\n");
        Files.writeString(pack.resolve("mmc-pack.json"), """
                {"components":[
                  {"uid":"net.minecraft","version":"1.21.1"},
                  {"uid":"net.neoforged","version":"21.1.250"}
                ]}
                """);
        var details = PrismInstances.details(CompanionProfile.forGame(pack.resolve("minecraft")));
        assertEquals("1.21.1", details.minecraftVersion());
        assertEquals("NeoForge 21.1.250", details.loader());
        assertEquals(icon, details.icon());
    }

    @Test void missingMetadataAndInvalidIconKeysDoNotPreventDiscovery() throws Exception {
        Path pack = Files.createDirectories(root.resolve("instances/My Pack/minecraft")).getParent();
        Files.writeString(pack.resolve("instance.cfg"), "iconKey=../outside\n");
        Files.write(root.resolve("outside.png"), new byte[0]);
        var profiles = PrismInstances.discover(root);
        assertEquals(1, profiles.size());
        assertEquals(new PrismInstances.Details("", "", null), PrismInstances.details(profiles.getFirst()));
    }

    @Test void discoversGameDirectoriesAndNames() throws Exception {
        Path pack = Files.createDirectories(root.resolve("instances/My Pack"));
        Path game = Files.createDirectory(pack.resolve(".minecraft"));
        Files.writeString(pack.resolve("instance.cfg"), "[General]\nname=Other configured name\niconKey=pack\n");
        var profiles = PrismInstances.discover(root);
        assertEquals(1, profiles.size());
        assertEquals(game, profiles.getFirst().workspaceDirectory());
        assertEquals("My Pack", ProjectRegistry.defaultName(profiles.getFirst()));
        assertEquals(game, PrismInstances.gameDirectory(pack));
        assertEquals(game, PrismInstances.gameDirectory(game));
        assertEquals("TotalDebug / run", ProjectRegistry.defaultName(CompanionProfile.forGame(root.resolve("TotalDebug/run"))));
    }
}
