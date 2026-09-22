package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import com.github.minecraft_ta.totalDebugCompanion.itemrender.integration.fusion.FusionItemRenderIntegration;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FusionTextureRegionTest {

    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;
    private static final int GUARD = 0xFFFFFF00;

    @TempDir
    Path temporaryDirectory;

    @Test
    void retainsFractionalBoundsAndClipsBoundaryTexels() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, RED);
        image.setRGB(1, 2, BLUE);
        TextureRegion region = new TextureRegion(image, 0.5, 0.25, 1.5, 2.5);

        assertSame(image, region.image());
        assertEquals(2, region.columns());
        assertEquals(3, region.rows());
        assertEquals(0, region.u0(0));
        assertEquals(1.0 / 3, region.u1(0), 1e-12);
        assertEquals(0.7, region.v0(2), 1e-12);
        assertEquals(1, region.v1(2));
        assertEquals(RED, region.sample(-1, -1));
        assertEquals(BLUE, region.sample(1, 1));
        assertEquals(BLUE, region.sample(2, 2));
    }

    @Test
    void preservesWholePixelTilesAndDeclaredAnimationFrame() throws Exception {
        BufferedImage image = new BufferedImage(16, 24, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, 0xFF000000 | x << 8 | y);
            }
        }
        TextureRegion region = FusionItemRenderIntegration.isolatedTexture(ItemModelId.parse("test:sheet"), image,
                JsonParser.parseString("""
                        {"fusion":{"type":"connecting","layout":"full"},
                         "animation":{"width":16,"height":12,"frames":[1,0]}}
                        """).getAsJsonObject());

        assertEquals(2, region.width());
        assertEquals(2, region.height());
        assertEquals(image.getRGB(0, 12), region.sample(0, Math.nextDown(0.5)),
                "adding the integer animation-frame offset must not move the sample into the next row");
        for (int y = 0; y < 17; y++) {
            for (int x = 0; x < 17; x++) {
                assertEquals(image.getRGB(x * 2 / 17, 12 + y * 2 / 17), region.sampleScaled(x, y, 17, 17));
            }
        }
    }

    @Test
    void keepsSquareLegacyFullLayoutBoundsFractional() throws Exception {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        TextureRegion region = FusionItemRenderIntegration.isolatedTexture(ItemModelId.parse("test:sheet"), image,
                JsonParser.parseString("""
                        {"fusion":{"type":"connecting"}}
                        """).getAsJsonObject());
        assertEquals(1.25, region.width());
        assertEquals(1.25, region.height());
    }

    @Test
    void standaloneGeneratedItemUsesExactFractionalBandHeights() throws Exception {
        prepareTextures();
        writeModels(generated("[0,0,0]"));
        assertModelsMatch();
        try (ItemRenderBackend backend = ItemRenderBackend.open(this.temporaryDirectory)) {
            BufferedImage image = backend.render(ItemRenderRequest.of("test:item/fractional", 24));
            assertEquals(RED, image.getRGB(12, 8));
            assertEquals(GREEN, image.getRGB(12, 9));
            assertEquals(GREEN, image.getRGB(12, 17));
            assertEquals(BLUE, image.getRGB(12, 18));
        }
    }

    @Test
    void transformedGeneratedEdgesUseClippedPartialRows() throws Exception {
        prepareTextures();
        for (String rotation : new String[]{"[0,-90,0]", "[-90,0,0]", "[25,-35,0]"}) {
            writeModels(generated(rotation));
            assertModelsMatch();
        }
    }

    @Test
    void elementFacesSampleWithinTheExactFractionalTile() throws Exception {
        prepareTextures();
        writeModels("""
                {"gui_light":"front", "textures":{"surface":"test:item/%s"},
                 "elements":[{"from":[0,0,8],"to":[16,16,8],
                   "faces":{"south":{"texture":"#surface"}}}]}
                """);
        assertModelsMatch();
    }

    private static String generated(String rotation) {
        return """
                {"parent":"builtin/generated", "gui_light":"front",
                 "textures":{"layer0":"test:item/%%s"},
                 "display":{"gui":{"rotation":%s}}}
                """.formatted(rotation);
    }

    private void prepareTextures() throws Exception {
        BufferedImage connecting = new BufferedImage(8, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < connecting.getHeight(); y++) {
            for (int x = 0; x < connecting.getWidth(); x++) {
                connecting.setRGB(x, y, GUARD);
            }
        }
        connecting.setRGB(0, 0, RED);
        connecting.setRGB(0, 1, GREEN);
        connecting.setRGB(0, 2, BLUE);
        writeTexture("fractional", connecting);
        write("assets/test/textures/item/fractional.png.mcmeta", """
                {"fusion":{"type":"connecting"}}
                """);

        // The 8/3-pixel region spans red and green rows fully, then 2/3 of the blue row.
        BufferedImage reference = new BufferedImage(1, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            reference.setRGB(0, y, y < 3 ? RED : y < 6 ? GREEN : BLUE);
        }
        writeTexture("reference", reference);
    }

    private void writeModels(String template) throws Exception {
        for (String name : new String[]{"fractional", "reference"}) {
            write("assets/test/models/item/" + name + ".json", template.formatted(name));
        }
    }

    private void assertModelsMatch() throws Exception {
        try (ItemRenderBackend backend = ItemRenderBackend.open(this.temporaryDirectory)) {
            BufferedImage actual = backend.render(ItemRenderRequest.of("test:item/fractional", 64));
            BufferedImage expected = backend.render(ItemRenderRequest.of("test:item/reference", 64));
            int visible = 0;
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), "pixel " + x + ", " + y);
                    if ((actual.getRGB(x, y) >>> 24) != 0) {
                        visible++;
                    }
                }
            }
            assertTrue(visible > 0);
        }
    }

    private void writeTexture(String name, BufferedImage image) throws Exception {
        Path path = this.temporaryDirectory.resolve("assets/test/textures/item/" + name + ".png");
        Files.createDirectories(path.getParent());
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }

    private void write(String resource, String value) throws Exception {
        Path path = this.temporaryDirectory.resolve(resource);
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }
}
