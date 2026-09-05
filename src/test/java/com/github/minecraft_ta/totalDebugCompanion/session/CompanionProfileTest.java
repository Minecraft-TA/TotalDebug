package com.github.minecraft_ta.totalDebugCompanion.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionProfileTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsTheOfflineProfileSnapshot() throws Exception {
        CompanionProfile profile = new CompanionProfile(
                "atm10",
                this.temporaryDirectory.resolve("data"),
                this.temporaryDirectory.resolve("workspace")
        );
        Path profileFile = this.temporaryDirectory.resolve("profile.properties");

        profile.writeAtomically(profileFile);

        assertEquals(profile, CompanionProfile.read(profileFile));
    }
}
