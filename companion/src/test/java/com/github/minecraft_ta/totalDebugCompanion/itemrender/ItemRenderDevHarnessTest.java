package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemRenderDevHarnessTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesStructuredAndPerModelReports() throws Exception {
        ItemRenderRequest rendered = ItemRenderRequest.of("alpha:item/rendered", 16);
        ItemRenderRequest unsupported = ItemRenderRequest.of("beta:item/unsupported", 16);
        ItemRenderBatchResult batch = new ItemRenderBatchResult(List.of(
                new ItemRenderBatchResult.Entry(rendered, null, null, 1_000_000),
                new ItemRenderBatchResult.Entry(
                        unsupported,
                        null,
                        new UnsupportedItemModelException(
                                unsupported.modelId(),
                                "custom model loader beta:special"
                        ),
                        3_000_000
                )
        ), 5_000_000);

        Path output = this.temporaryDirectory.resolve("report");
        ItemRenderDevHarness.writeReport(
                output,
                Instant.parse("2026-08-31T12:00:00Z"),
                List.of(new ItemRenderResourceRoot(this.temporaryDirectory.resolve("resources.jar"))),
                Set.of(),
                16,
                batch
        );

        String json = Files.readString(output.resolve("summary.json"));
        assertTrue(json.contains("\"models\": 2"));
        assertTrue(json.contains("\"rendered\": 1"));
        assertTrue(json.contains("custom model loader beta:special"));
        assertTrue(json.contains("\"failureDetails\""));
        assertTrue(json.contains("\"namespace\": \"alpha\""));
        String text = Files.readString(output.resolve("summary.txt"));
        assertTrue(text.contains("Coverage: 50.00%"));
        String csv = Files.readString(output.resolve("models.csv"));
        assertTrue(csv.contains("\"alpha:item/rendered\",\"alpha\",rendered"));
        assertTrue(csv.contains("UNSUPPORTED_FEATURE"));
    }
}
