package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FluidContainerRenderTest {
    @TempDir Path pack;

    @Test
    void masksFluidWithoutLosingItsTextureResolutionOrMultiplyingMaskAlpha() throws Exception {
        setup("", false);
        png("mask", 2, 1, 0x01000000, 0);
        png("liquid", 4, 1, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00, 0xFFFF00FF);
        BufferedImage image = render();
        assertEquals(0xFFFF0000, image.getRGB(1, 8));
        assertEquals(0xFF00FF00, image.getRGB(5, 8));
        assertEquals(0xFF0000FF, image.getRGB(12, 8));
    }

    @Test
    void coverMaskRestoresBaseOverFluidAndTextureCoverUsesItsOwnPixels() throws Exception {
        setup(",\"textures\":{\"cover\":\"test:item/cover\"}", false);
        png("cover", 2, 1, 0xFFFFFF00, 0);
        assertEquals(0xFF0000FF, render().getRGB(1, 8));
        model(",\"cover_is_mask\":false,\"textures\":{\"cover\":\"test:item/cover\"}");
        assertEquals(0xFFFFFF00, render().getRGB(1, 8));
    }

    @Test
    void inheritsFluidLoaderAndFlipsTheEntireContainerForGas() throws Exception {
        setup(",\"flip_gas\":true", true);
        png("mask", 2, 1, 0xFFFFFFFF, 0);
        write("assets/test/models/item/child.json", "{\"parent\":\"test:item/container\"}");
        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            BufferedImage image = backend.render(ItemRenderRequest.of("test:item/child", 16));
            assertEquals(0xFF0000FF, image.getRGB(1, 8));
            assertEquals(0xFFFF0000, image.getRGB(12, 8));
        }
    }

    @Test
    void emptyFluidNeedsNoAppearanceCapture() throws Exception {
        setup(",\"fluid\":\"minecraft:empty\"", false);
        assertEquals(0xFF0000FF, render().getRGB(1, 8));
    }

    @Test
    void missingFluidAppearanceIsExplicitInsteadOfGuessingATexture() throws Exception {
        setup(",\"fluid\":\"test:uncaptured\"", false);
        ItemRenderException failure = assertThrows(ItemRenderException.class, this::render);
        assertEquals("missing captured fluid appearance", failure.detail());
        assertTrue(failure.getMessage().contains("test:uncaptured"));
    }

    @Test
    void selectedFluidAndStackTintHaveSeparateCachedImages() throws Exception {
        setup("", false);
        png("liquid", 1, 1, 0xFFFFFFFF);
        Path overrides = pack.resolve("overrides");
        write("overrides/totaldebug/fluid-appearances.json", """
                {"schemaVersion":1,"fluids":{
                  "test:liquid":{"stillTexture":"test:item/liquid","tint":"FF00FF00","lightLevel":0,"lighterThanAir":false},
                  "test:other":{"stillTexture":"test:item/liquid","tint":"FFFFFF00","lightLevel":15,"lighterThanAir":false}
                }}
                """);
        try (ItemRenderBackend backend = ItemRenderBackend.open(pack, overrides)) {
            var original = ItemRenderRequest.of("test:item/container", 16);
            var other = original.withFluid(ItemModelId.parse("test:other"));
            var tinted = new ItemRenderRequest(original.modelId(), 16, Map.of(1, 0xFFFF00FF), other.fluidId());
            assertEquals(0xFF00FF00, backend.render(original).getRGB(8, 8));
            assertEquals(0xFFFFFF00, backend.render(other).getRGB(8, 8));
            assertEquals(0xFFFF00FF, backend.render(tinted).getRGB(8, 8));
            assertEquals(0xFF0000FF, backend.render(original.withFluid(ItemModelId.parse("minecraft:empty"))).getRGB(8, 8));
            assertEquals(0xFF00FF00, backend.render(other.withFluid(null)).getRGB(8, 8));
        }
    }

    @Test
    void malformedCaptureReportsAResourceError() throws Exception {
        setup("", false);
        write("totaldebug/fluid-appearances.json", "{\"schemaVersion\":2,\"fluids\":{}}");
        ItemRenderException failure = assertThrows(ItemRenderException.class, this::render);
        assertEquals(ItemRenderException.Kind.RESOURCE_ERROR, failure.kind());
        assertEquals("invalid fluid appearance capture", failure.detail());
    }

    @Test
    void animatedMaskReportsItsUnsupportedGeometry() throws Exception {
        setup("", false);
        write("assets/test/textures/item/mask.png.mcmeta", "{\"animation\":{}}");
        ItemRenderException failure = assertThrows(ItemRenderException.class, this::render);
        assertEquals("animated fluid-container mask", failure.detail());
    }

    private void setup(String extra, boolean gas) throws Exception {
        write("assets/test/models/item/base.json", """
                {"textures":{"base":"test:item/base","fluid":"test:item/mask"}}
                """);
        model(extra);
        png("base", 1, 1, 0xFF0000FF);
        png("mask", 1, 1, 0xFFFFFFFF);
        png("liquid", 1, 1, 0xFFFF0000);
        write("totaldebug/fluid-appearances.json", """
                {"schemaVersion":1,"fluids":{"test:liquid":{"stillTexture":"test:item/liquid",
                "tint":"FFFFFFFF","lightLevel":0,"lighterThanAir":%s}}}
                """.formatted(gas));
    }

    private void model(String extra) throws Exception {
        write("assets/test/models/item/container.json", """
                {"parent":"test:item/base","loader":"neoforge:fluid_container","fluid":"test:liquid"%s}
                """.formatted(extra));
    }

    private BufferedImage render() throws Exception {
        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            return backend.render(ItemRenderRequest.of("test:item/container", 16));
        }
    }

    private void write(String path, String text) throws Exception {
        Path target = pack.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, text);
    }

    private void png(String name, int width, int height, int... pixels) throws Exception {
        Path target = pack.resolve("assets/test/textures/item/" + name + ".png");
        Files.createDirectories(target.getParent());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        assertTrue(ImageIO.write(image, "png", target.toFile()));
    }
}
