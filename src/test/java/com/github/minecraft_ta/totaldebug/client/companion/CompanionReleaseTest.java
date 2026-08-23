package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionReleaseTest {
    private static final String PUBLISHED_SHA256 =
            "7f09e350acb7dbcc8a8a994d6ba2a8340b7226e67ae6acf69ba9ee61babf7157";

    @Test
    void loadsTheGeneratedImmutableReleaseMetadata() {
        CompanionRelease release = CompanionRelease.loadBundled();

        assertEquals("2.0.0", release.version());
        assertEquals("TotalDebugCompanion.jar", release.artifactFileName());
        assertEquals(
                URI.create(
                        "https://github.com/Minecraft-TA/TotalDebugCompanion/releases/download/v2.0.0/"
                                + "TotalDebugCompanion.jar"
                ),
                release.downloadUri()
        );
        assertEquals(
                System.getProperty("totaldebug.test.companionSha256", PUBLISHED_SHA256),
                release.sha256()
        );
    }

    @Test
    void rejectsReleaseMetadataThatCannotBeAnIntegrityPin() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionRelease(
                        "2.0.0",
                        "TotalDebugCompanion.jar",
                        URI.create("https://example.invalid/TotalDebugCompanion.jar"),
                        "not-a-sha256"
                )
        );
    }
}
