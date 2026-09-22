package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

class RendererIsolationTest {
    @TempDir Path root;

    @Test void cacheEvictsLeastRecentlyUsedAndDoesNotRetainOversizedValues() {
        var cache = new RenderCache<String, String>(4, String::length);
        cache.put("a", "aa"); cache.put("b", "bb");
        assertEquals("aa", cache.get("a"));
        cache.put("c", "cc");
        assertNull(cache.get("b"));
        assertEquals("aa", cache.get("a"));
        cache.put("a", "oversized");
        assertNull(cache.get("a"));
        assertEquals("cc", cache.get("c"));
    }

    @Test void higherPackTextureDoesNotInheritLowerAnimationMetadata() throws Exception {
        Path low = root.resolve("low"), high = root.resolve("high");
        png(low, 1, 2, 0xFF00FF00);
        Files.writeString(low.resolve("assets/test/textures/sprite.png.mcmeta"), "{\"animation\":{\"frames\":[1]}}");
        png(high, 1, 1, 0xFFFF0000);
        try (var resources = ResourcePackStack.open(List.of(new ItemRenderResourceRoot(low), new ItemRenderResourceRoot(high)))) {
            assertEquals(0xFFFF0000, new ItemModelRepository(resources).texture(ItemModelId.parse("test:sprite")).image().getRGB(0, 0));
        }
    }

    @Test void transparentTintSurvivesRenderingAndCachedImageCopy() throws Exception {
        png(root, 1, 1, 0xFFFFFFFF);
        Path model = root.resolve("assets/test/models/item/sample.json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "{\"parent\":\"builtin/generated\",\"gui_light\":\"front\",\"textures\":{\"layer0\":\"test:sprite\"}}");
        try (var renderer = ItemRenderBackend.open(root)) {
            var request = new ItemRenderRequest(ItemModelId.parse("test:item/sample"), 32, Map.of(0, 0x01112233));
            var first = renderer.render(request);
            assertEquals(0x01112233, first.getRGB(16, 16));
            first.setRGB(16, 16, 0);
            assertEquals(0x01112233, renderer.render(request).getRGB(16, 16));
        }
    }

    @Test void rejectsOversizedPngFromHeaderBeforeDecodingPixels() throws Exception {
        png(root, 1, 1, 0xFFFFFFFF);
        Path file = root.resolve("assets/test/textures/sprite.png");
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).putInt(16, 100_000).putInt(20, 100_000);
        CRC32 crc = new CRC32(); crc.update(bytes, 12, 17);
        ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());
        Files.write(file, bytes);
        try (var resources = ResourcePackStack.open(List.of(new ItemRenderResourceRoot(root)))) {
            var failure = assertThrows(ItemRenderException.class, () -> new ItemModelRepository(resources).texture(ItemModelId.parse("test:sprite")));
            assertEquals("texture pixel limit", failure.detail());
        }
    }

    private void png(Path pack, int width, int height, int color) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<height;y++) for(int x=0;x<width;x++) image.setRGB(x,y,color);
        Path file = pack.resolve("assets/test/textures/sprite.png");
        Files.createDirectories(file.getParent());
        ImageIO.write(image,"png",file.toFile());
    }
}
