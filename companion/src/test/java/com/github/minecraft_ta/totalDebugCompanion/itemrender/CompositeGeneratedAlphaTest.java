package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeGeneratedAlphaTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void singleGeneratedChildPreservesStandaloneAlpha() throws Exception {
        writeModels(0);
        try (ItemRenderBackend backend = ItemRenderBackend.open(this.temporaryDirectory)) {
            int standalone = backend.render(ItemRenderRequest.of("test:item/standalone", 32)).getRGB(16, 16);
            int composite = backend.render(ItemRenderRequest.of("test:item/composite", 32)).getRGB(16, 16);
            assertEquals(0x80FF0000, standalone);
            assertEquals(standalone, composite, "A composite wrapper must not blend the generated back face again");
        }
    }

    @Test
    void modestGuiRotationDoesNotBlendTheHiddenGeneratedBackFace() throws Exception {
        for (int rotation : new int[]{-20, 20}) {
            writeModels(rotation);
            try (ItemRenderBackend backend = ItemRenderBackend.open(this.temporaryDirectory)) {
                for (String model : new String[]{"standalone", "composite"}) {
                    int pixel = backend.render(ItemRenderRequest.of("test:item/" + model, 32)).getRGB(16, 16);
                    assertEquals(0x80FF0000, pixel, model + " at GUI Y rotation " + rotation);
                }
            }
        }
    }

    @Test
    void generatedBackFaceRemainsVisibleWhenTurnedAround() throws Exception {
        writeModels(180);
        try (ItemRenderBackend backend = ItemRenderBackend.open(this.temporaryDirectory)) {
            for (String model : new String[]{"standalone", "composite"}) {
                assertEquals(0x80FF0000,
                        backend.render(ItemRenderRequest.of("test:item/" + model, 32)).getRGB(16, 16), model);
            }
        }
    }

    @Test
    void reflectionsKeepTheOutwardGeneratedFaceInFrontOfAnOpaqueMidplane() throws Exception {
        writeModels(0);
        Path models = this.temporaryDirectory.resolve("assets/test/models/item");
        BufferedImage blue = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        blue.setRGB(0, 0, 0xFF0000FF);
        assertTrue(ImageIO.write(blue, "png",
                this.temporaryDirectory.resolve("assets/test/textures/item/blue.png").toFile()));
        for (String reflection : new String[]{
                "\"display\":{\"gui\":{\"scale\":[-1,1,1]}}",
                "\"transform\":{\"origin\":\"center\",\"scale\":[-1,1,1]}"
        }) {
            Files.writeString(models.resolve("reflected.json"), """
                    {"loader":"neoforge:composite","gui_light":"front",%s,
                     "children":{
                       "sprite":{"parent":"builtin/generated","textures":{"layer0":"test:item/pixel"}},
                       "midplane":{"textures":{"surface":"test:item/blue"},
                         "elements":[{"from":[0,0,8],"to":[16,16,8],
                           "faces":{"south":{"texture":"#surface"}}}]}
                     },"item_render_order":["sprite","midplane"]}
                    """.formatted(reflection));
            try (ItemRenderBackend backend = ItemRenderBackend.open(this.temporaryDirectory)) {
                assertEquals(0xFF80007F,
                        backend.render(ItemRenderRequest.of("test:item/reflected", 32)).getRGB(16, 16), reflection);
            }
        }
    }

    private void writeModels(int rotation) throws Exception {
        Path models = this.temporaryDirectory.resolve("assets/test/models/item");
        Files.createDirectories(models);
        String display = "\"display\":{\"gui\":{\"rotation\":[0," + rotation + ",0]}}";
        String child = """
                {"parent":"builtin/generated","textures":{"layer0":"test:item/pixel"}}
                """;
        Files.writeString(models.resolve("standalone.json"), """
                {"parent":"builtin/generated","gui_light":"front",%s,
                 "textures":{"layer0":"test:item/pixel"}}
                """.formatted(display));
        Files.writeString(models.resolve("composite.json"), """
                {"loader":"neoforge:composite","gui_light":"front",%s,
                 "children":{"only":%s},"item_render_order":["only"]}
                """.formatted(display, child));
        Path texture = this.temporaryDirectory.resolve("assets/test/textures/item/pixel.png");
        Files.createDirectories(texture.getParent());
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x80FF0000);
        assertTrue(ImageIO.write(image, "png", texture.toFile()));
    }
}
