package com.github.minecraft_ta.totalDebugCompanion.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionProfileTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsThePreviouslyRememberedProfile() throws Exception {
        CompanionProfile profile = new CompanionProfile(
                "atm10",
                this.temporaryDirectory.resolve("data"),
                this.temporaryDirectory.resolve("workspace")
        );
        Path profileFile = this.temporaryDirectory.resolve("profile.properties");

        com.github.minecraft_ta.totaldebug.storage.JsonFiles.write(profileFile, profile.toJson());

        assertEquals(profile, CompanionProfile.read(profileFile));
    }
}
