package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemRenderDevHarnessTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void orderedRootsAndExactModelSelectionReportEmptyPreviewsWithoutRetainingImages() throws Exception {
        Path pack = this.temporaryDirectory.resolve("pack");
        Path override = this.temporaryDirectory.resolve("override");
        for (String name : List.of("visible", "empty", "unselected")) {
            Path model = pack.resolve("assets/test/models/item/" + name + ".json");
            Files.createDirectories(model.getParent());
            Files.writeString(model, """
                    {"parent":"builtin/generated","textures":{"layer0":"test:item/%s"}}
                    """.formatted(name.equals("empty") ? "empty" : "visible"));
        }
        writePixel(pack, "visible", 0xFFFF0000);
        writePixel(pack, "empty", 0);
        writePixel(override, "visible", 0xFF00FF00);
        Path rootsFile = this.temporaryDirectory.resolve("roots.json");
        Files.writeString(rootsFile, new Gson().toJson(List.of(pack.toString())));
        Path output = this.temporaryDirectory.resolve("selected");
        String[] arguments = {
                "--roots-file=" + rootsFile, "--root=" + override, "--output=" + output,
                "--model=test:item/visible", "--model=test:item/empty", "--model=test:item/absent",
                "--size=4", "--progress=0"
        };
        ItemRenderDevHarness.main(arguments);
        var totals = JsonParser.parseString(Files.readString(output.resolve("summary.json")))
                .getAsJsonObject().getAsJsonObject("totals");
        assertEquals(3, totals.get("models").getAsInt());
        assertEquals(2, totals.get("rendered").getAsInt());
        assertEquals(1, totals.get("visible").getAsInt());
        assertEquals(1, totals.get("empty").getAsInt());
        assertEquals(1, totals.get("failed").getAsInt());
        assertTrue(Files.notExists(output.resolve("images")));

        String[] withImages = java.util.Arrays.copyOf(arguments, arguments.length + 1);
        withImages[arguments.length] = "--write-images";
        ItemRenderDevHarness.main(withImages);
        BufferedImage visible = ImageIO.read(output.resolve("images/test/item/visible.png").toFile());
        assertEquals(0xFF00FF00, visible.getRGB(0, 0));
    }

    private void writePixel(Path root, String name, int argb) throws Exception {
        Path path = root.resolve("assets/test/textures/item/" + name + ".png");
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }

    @Test
    void writesStructuredAndPerModelReports() throws Exception {
        ItemRenderRequest rendered = ItemRenderRequest.of("alpha:item/rendered", 16);
        ItemRenderRequest unsupported = ItemRenderRequest.of("beta:item/unsupported", 16);
        ItemRenderBatchResult batch = new ItemRenderBatchResult(List.of(
                new ItemRenderBatchResult.Entry(rendered, null, null, 16, 1_000_000),
                new ItemRenderBatchResult.Entry(
                        unsupported,
                        null,
                        new UnsupportedItemModelException(
                                unsupported.modelId(),
                                "custom model loader beta:special"
                        ),
                        0,
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
        assertTrue(text.contains("Models without failure: 50.00%"));
        assertTrue(text.contains("With visible pixels: 1"));
        assertTrue(json.contains("\"visible\": 1"));
        String csv = Files.readString(output.resolve("models.csv"));
        assertTrue(csv.contains("\"alpha:item/rendered\",\"alpha\",rendered"));
        assertTrue(csv.contains("UNSUPPORTED_FEATURE"));
    }
}
