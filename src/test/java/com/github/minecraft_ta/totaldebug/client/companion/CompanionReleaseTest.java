package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionReleaseTest {
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
                "c7f6bf3f63e918aae939f83ddbae68cf2fad904162a387db779f484ea893ea8a",
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
