package com.github.minecraft_ta.totalDebugCompanion.inspection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidTexturesTest {
    @Test
    void tintsTheFirstFrameOfTheCapturedStillTexture(@TempDir Path directory) throws Exception {
        BufferedImage strip = new BufferedImage(2, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 2; x++) {
                strip.setRGB(x, y, y < 2 ? 0xFFFFFFFF : 0xFF000000);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(strip, "png", png);
        Path archive = directory.resolve("snapshot.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(FluidTextures.APPEARANCES));
            zip.write("""
                    {"schemaVersion":1,"fluids":{"minecraft:water":{"stillTexture":"minecraft:block/water_still",
                    "tint":"FF3F76E4","lightLevel":0,"lighterThanAir":false}}}
                    """.getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("layers/0/assets/minecraft/textures/block/water_still.png"));
            zip.write(png.toByteArray());
        }

        FluidTextures textures = FluidTextures.open(archive);
        BufferedImage water = textures.texture("minecraft:water").orElseThrow();

        assertEquals(2, water.getWidth());
        assertEquals(2, water.getHeight());
        assertEquals(0xFF3F76E4, water.getRGB(1, 1));
        assertTrue(textures.texture("minecraft:lava").isEmpty());
    }
}
