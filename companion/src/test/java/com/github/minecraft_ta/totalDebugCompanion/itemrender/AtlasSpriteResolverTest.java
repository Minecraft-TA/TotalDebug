package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasSpriteResolverTest {

    @TempDir
    Path pack;

    @Test
    void mapsPaletteRgbAndMultipliesAlphaWithoutChangingUnmappedPixels() throws Exception {
        atlas(paletteSource());
        png("test:trim", 0x80112233, 0xFF445566, 0xFF778899, 0x00112233);
        png("test:key", 0x40112233, 0x00445566);
        png("test:palette", 0x80ABCDEF, 0xFF000000);
        try (ResourcePackStack resources = roots()) {
            BufferedImage image = new ItemModelRepository(resources).texture(ItemModelId.parse("test:trim_gold")).image();
            assertEquals(0x40ABCDEF, image.getRGB(0, 0));
            assertEquals(0xFF445566, image.getRGB(1, 0));
            assertEquals(0xFF778899, image.getRGB(2, 0));
            assertEquals(0x00112233, image.getRGB(3, 0));
        }
    }

    @Test
    void mergesAtlasResourcesAndReadsPalettesFromHighestPriorityNestedArchive() throws Exception {
        atlas(paletteSource());
        png("test:trim", 0xFF112233);
        png("test:key", 0xFF112233);
        png("test:palette", 0xFFAA0000);
        Path archive = pack.resolve("override.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("nested/assets/minecraft/atlases/blocks.json"));
            zip.write("{\"sources\":[]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("nested/assets/test/textures/palette.png"));
            zip.write(pngBytes(0xFF00BB00));
            zip.closeEntry();
        }
        try (ResourcePackStack resources = ResourcePackStack.open(List.of(
                new ItemRenderResourceRoot(pack), ItemRenderResourceRoot.nested(archive, "nested")))) {
            assertEquals(0xFF00BB00, new ItemModelRepository(resources)
                    .texture(ItemModelId.parse("test:trim_gold")).image().getRGB(0, 0));
        }
    }

    @Test
    void laterAtlasSourcesOverrideEarlierSourcesEvenWhenGeneratedPngExists() throws Exception {
        png("test:trim", 0xFF112233);
        png("test:key", 0xFF112233);
        png("test:palette", 0xFFAA0000);
        png("test:trim_gold", 0xFF00BB00);
        atlas("{\"type\":\"single\",\"resource\":\"test:trim_gold\"}," + paletteSource());
        try (ResourcePackStack resources = roots()) {
            assertEquals(0xFFAA0000, new ItemModelRepository(resources)
                    .texture(ItemModelId.parse("test:trim_gold")).image().getRGB(0, 0));
        }
        atlas(paletteSource() + ",{\"type\":\"single\",\"resource\":\"test:trim_gold\"}");
        try (ResourcePackStack resources = roots()) {
            assertEquals(0xFF00BB00, new ItemModelRepository(resources)
                    .texture(ItemModelId.parse("test:trim_gold")).image().getRGB(0, 0));
        }
    }

    @Test
    void filtersRemoveEarlierDefinitionsAndLaterDirectorySourcesCanAddThemAgain() throws Exception {
        png("test:item/trim_gold", 0xFF00BB00);
        String directory = "{\"type\":\"directory\",\"source\":\"item\",\"prefix\":\"item/\"}";
        String filter = "{\"type\":\"filter\",\"pattern\":{\"namespace\":\"test\",\"path\":\"trim\"}}";
        atlas(directory + "," + filter);
        try (ResourcePackStack resources = roots()) {
            ItemRenderException failure = assertThrows(ItemRenderException.class,
                    () -> new ItemModelRepository(resources).texture(ItemModelId.parse("test:item/trim_gold")));
            assertEquals(ItemRenderException.Kind.MISSING_RESOURCE, failure.kind());
        }
        atlas(directory + "," + filter + "," + directory);
        try (ResourcePackStack resources = roots()) {
            assertEquals(0xFF00BB00, new ItemModelRepository(resources)
                    .texture(ItemModelId.parse("test:item/trim_gold")).image().getRGB(0, 0));
        }
    }

    @Test
    void rejectsUnequalPalettePixelCounts() throws Exception {
        atlas(paletteSource());
        png("test:trim", 0xFF112233);
        png("test:key", 0xFF112233, 0xFF445566);
        png("test:palette", 0xFFAA0000);
        try (ResourcePackStack resources = roots()) {
            ItemRenderException failure = assertThrows(ItemRenderException.class,
                    () -> new ItemModelRepository(resources).texture(ItemModelId.parse("test:trim_gold")));
            assertEquals(ItemRenderException.Kind.RESOURCE_ERROR, failure.kind());
            assertTrue(failure.getMessage().contains("different pixel counts"));
        }
    }

    @Test
    void missingPaletteReportsItsResourcePath() throws Exception {
        atlas(paletteSource());
        png("test:trim", 0xFF112233);
        png("test:key", 0xFF112233);
        try (ResourcePackStack resources = roots()) {
            ItemRenderException failure = assertThrows(ItemRenderException.class,
                    () -> new ItemModelRepository(resources).texture(ItemModelId.parse("test:trim_gold")));
            assertEquals(ItemRenderException.Kind.MISSING_RESOURCE, failure.kind());
            assertEquals("assets/test/textures/palette.png", failure.detail());
        }
    }

    @Test
    void ignoresUnknownSourceForKnownSpritesAndReportsItForUnresolvedSprites() throws Exception {
        atlas(paletteSource() + ",{\"type\":\"example:custom\"}");
        png("test:trim", 0xFF112233);
        png("test:key", 0xFF112233);
        png("test:palette", 0xFFAA0000);
        try (ResourcePackStack resources = roots()) {
            ItemModelRepository repository = new ItemModelRepository(resources);
            assertEquals(0xFFAA0000, repository.texture(ItemModelId.parse("test:trim_gold")).image().getRGB(0, 0));
            ItemRenderException failure = assertThrows(ItemRenderException.class,
                    () -> repository.texture(ItemModelId.parse("test:unknown")));
            assertEquals(ItemRenderException.Kind.MISSING_RESOURCE, failure.kind());
            assertEquals("assets/test/textures/unknown.png", failure.detail());
            assertTrue(failure.getMessage().contains("example:custom"));
        }
    }

    private ResourcePackStack roots() throws Exception {
        return ResourcePackStack.open(List.of(new ItemRenderResourceRoot(pack)));
    }

    private void atlas(String sources) throws Exception {
        Path target = pack.resolve("assets/minecraft/atlases/blocks.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "{\"sources\":[" + sources + "]}");
    }

    private void png(String id, int... pixels) throws Exception {
        Path target = pack.resolve(ItemModelId.parse(id).textureResourcePath());
        Files.createDirectories(target.getParent());
        Files.write(target, pngBytes(pixels));
    }

    private static byte[] pngBytes(int... pixels) throws Exception {
        BufferedImage image = new BufferedImage(pixels.length, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, pixels.length, 1, pixels, 0, pixels.length);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", bytes);
        return bytes.toByteArray();
    }

    private static String paletteSource() {
        return """
                {"type":"minecraft:paletted_permutations","textures":["test:trim"],
                 "palette_key":"test:key","permutations":{"gold":"test:palette"}}
                """;
    }
}
