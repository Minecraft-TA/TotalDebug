package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextureImagesTest {
    @Test
    void anImageDeclaringMorePixelsThanTheLimitIsRefusedBeforeItsPixelsExist() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", out);
        byte[] small = out.toByteArray();
        byte[] huge = small.clone();
        // The IHDR chunk follows the 8-byte signature: length, type, then width and height; its CRC covers type and data.
        ByteBuffer header = ByteBuffer.wrap(huge);
        header.putInt(16, 100_000);
        header.putInt(20, 100_000);
        CRC32 crc = new CRC32();
        crc.update(huge, 12, 17);
        header.putInt(29, (int) crc.getValue());

        assertThrows(TextureImages.TooLarge.class, () -> TextureImages.decode(huge, 16L * 1024 * 1024));
        assertEquals(2, TextureImages.decode(small, 16L * 1024 * 1024).getWidth());
    }
}
