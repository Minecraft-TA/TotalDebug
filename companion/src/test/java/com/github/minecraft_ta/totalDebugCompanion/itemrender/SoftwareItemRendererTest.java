package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftwareItemRendererTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void blendsEachPixelOfTranslucentQuadOnceForEitherWinding() throws Exception {
        BufferedImage texture = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        texture.setRGB(0, 0, 0x80FFFFFF);
        writeTexture(texture);
        for (int xScale : new int[]{1, -1}) {
            writeModel("""
                    {
                      "gui_light": "front",
                      "textures": { "surface": "test:item/pixels" },
                      "display": { "gui": { "scale": [%d, 1, 1] } },
                      "elements": [{
                        "from": [0, 0, 8], "to": [16, 16, 8],
                        "faces": { "south": { "texture": "#surface" } }
                      }]
                    }
                    """.formatted(xScale));

            BufferedImage rendered = render(4);
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    assertEquals(0x80FFFFFF, rendered.getRGB(x, y),
                            "pixel " + x + ", " + y + " with x scale " + xScale);
                }
            }
        }
    }

    @Test
    void samplesRightExtrusionFromTheOpaquePixelBeforeItsTransparentNeighbor() throws Exception {
        BufferedImage texture = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
        texture.setRGB(0, 0, 0xFFFF0000);
        texture.setRGB(1, 0, 0xFF00FF00);
        writeTexture(texture);
        writeGeneratedModel("[0, -90, 0]");

        assertEquals(0xFF00FF00, render(32).getRGB(16, 16));
    }

    @Test
    void samplesBottomExtrusionFromTheOpaquePixelBeforeItsTransparentNeighbor() throws Exception {
        BufferedImage texture = new BufferedImage(1, 3, BufferedImage.TYPE_INT_ARGB);
        texture.setRGB(0, 0, 0xFFFF0000);
        texture.setRGB(0, 1, 0xFF00FF00);
        writeTexture(texture);
        writeGeneratedModel("[-90, 0, 0]");

        assertEquals(0xFF00FF00, render(32).getRGB(16, 16));
    }

    @Test
    void compositesTranslucentForegroundOverOpaqueBackgroundInEitherChildOrder() throws Exception {
        writeSolidTexture("foreground", 0x80FF0000);
        writeSolidTexture("background", 0xFF0000FF);
        for (String order : new String[]{"[\"foreground\", \"background\"]", "[\"background\", \"foreground\"]"}) {
            writeCompositeModel(plane("foreground", 12), plane("background", 4), order);

            assertEquals(0xFF80007F, render(32).getRGB(8, 16), order);
        }
    }

    @Test
    void opaqueForegroundOccludesGeneratedChildInEitherChildOrder() throws Exception {
        writeSolidTexture("foreground", 0xFF0000FF);
        writeSolidTexture("background", 0x80FF0000);
        String generated = """
                {
                  "parent": "builtin/generated",
                  "textures": { "layer0": "test:item/background" }
                }
                """;
        for (String order : new String[]{"[\"foreground\", \"background\"]", "[\"background\", \"foreground\"]"}) {
            writeCompositeModel(plane("foreground", 12), generated, order);

            assertEquals(0xFF0000FF, render(32).getRGB(8, 16), order);
        }
    }

    private void writeCompositeModel(String foreground, String background, String order) throws IOException {
        writeModel("""
                {
                  "loader": "neoforge:composite",
                  "gui_light": "front",
                  "children": { "foreground": %s, "background": %s },
                  "item_render_order": %s
                }
                """.formatted(foreground, background, order));
    }

    private static String plane(String texture, int z) {
        return """
                {
                  "textures": { "surface": "test:item/%s" },
                  "elements": [{
                    "from": [0, 0, %d], "to": [16, 16, %d],
                    "faces": { "south": { "texture": "#surface" } }
                  }]
                }
                """.formatted(texture, z, z);
    }

    private void writeSolidTexture(String name, int color) throws IOException {
        BufferedImage texture = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        texture.setRGB(0, 0, color);
        writeTexture(name, texture);
    }

    private void writeGeneratedModel(String rotation) throws IOException {
        writeModel("""
                {
                  "parent": "builtin/generated",
                  "gui_light": "front",
                  "textures": { "layer0": "test:item/pixels" },
                  "display": { "gui": { "rotation": %s } }
                }
                """.formatted(rotation));
    }

    private void writeModel(String json) throws IOException {
        Path path = this.temporaryDirectory.resolve("assets/test/models/item/model.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, json);
    }

    private void writeTexture(BufferedImage texture) throws IOException {
        writeTexture("pixels", texture);
    }

    private void writeTexture(String name, BufferedImage texture) throws IOException {
        Path path = this.temporaryDirectory.resolve("assets/test/textures/item/" + name + ".png");
        Files.createDirectories(path.getParent());
        assertTrue(ImageIO.write(texture, "png", path.toFile()));
    }

    private BufferedImage render(int size) throws IOException {
        try (ItemRenderBackend backend = ItemRenderBackend.open(this.temporaryDirectory)) {
            return backend.render(ItemRenderRequest.of("test:item/model", size));
        }
    }
}
