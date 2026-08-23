package com.github.minecraft_ta.totalDebugCompanion.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionLaunchConfigurationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void supportsStandaloneLaunchAndExplicitAppHome() {
        CompanionLaunchConfiguration standalone = CompanionLaunchConfiguration.parse(
                new String[0],
                Map.of("LOCALAPPDATA", this.temporaryDirectory.toString())
        );
        assertEquals(
                this.temporaryDirectory.resolve("TotalDebugCompanion").toAbsolutePath().normalize(),
                standalone.appHome()
        );

        CompanionLaunchConfiguration explicit = CompanionLaunchConfiguration.parse(
                new String[]{"--app-home", this.temporaryDirectory.resolve("custom").toString()},
                Map.of()
        );
        assertEquals(this.temporaryDirectory.resolve("custom").toAbsolutePath().normalize(), explicit.appHome());
        assertEquals(explicit.appHome().resolve("instance.properties"), explicit.descriptorFile());
        assertEquals(explicit.appHome().resolve("instance.key"), explicit.keyFile());
    }

    @Test
    void rejectsTheOldPerSessionLaunchShape() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CompanionLaunchConfiguration.parse(new String[]{"--data-directory", "data"}, Map.of())
        );
    }
}
