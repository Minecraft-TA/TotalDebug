package com.github.minecraft_ta.totalDebugCompanion.resource;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceLoaderTest {

    @Test
    void opensUnknownUtf8AsPlainText() throws Exception {
        byte[] bytes = "custom format\nvalue = hello\n".getBytes(StandardCharsets.UTF_8);
        LoadedResource.Text text = assertInstanceOf(
                LoadedResource.Text.class,
                ResourceLoader.load(source("custom.thing", bytes), FileTypeResolver.resolve("custom.thing"))
        );

        assertEquals("custom format\nvalue = hello\n", text.value());
        assertEquals("UTF-8", text.charsetName());
    }

    @Test
    void rejectsUnknownBinaryData() {
        byte[] bytes = {0x01, 0x02, 0, 0x04};
        assertThrows(
                UnsupportedResourceException.class,
                () -> ResourceLoader.load(source("unknown.data2", bytes), FileTypeResolver.resolve("unknown.data2"))
        );
    }

    @Test
    void decodesBomMarkedUtf16() throws Exception {
        byte[] value = "hello".getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[value.length + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xFE;
        System.arraycopy(value, 0, bytes, 2, value.length);

        LoadedResource.Text text = assertInstanceOf(
                LoadedResource.Text.class,
                ResourceLoader.load(source("custom", bytes), FileTypeResolver.resolve("custom"))
        );
        assertEquals("hello", text.value());
        assertEquals("UTF-16LE", text.charsetName());
    }

    @Test
    void decodesPngAndKeepsItsDimensions() throws Exception {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, Color.MAGENTA.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);

        LoadedResource.Image loaded = assertInstanceOf(
                LoadedResource.Image.class,
                ResourceLoader.load(source("texture.png", output.toByteArray()), FileTypeResolver.resolve("texture.png"))
        );
        assertEquals(3, loaded.value().getWidth());
        assertEquals(2, loaded.value().getHeight());
        assertEquals(Color.MAGENTA.getRGB(), loaded.value().getRGB(1, 1));
        loaded.value().flush();
    }

    private static ContentSource source(String name, byte[] bytes) {
        return new ContentSource() {
            @Override
            public String identity() {
                return name;
            }

            @Override
            public String displayName() {
                return name;
            }

            @Override
            public String tooltip() {
                return name;
            }

            @Override
            public long declaredSize() {
                return bytes.length;
            }

            @Override
            public byte[] read(int maximumBytes) throws ResourceTooLargeException {
                if (bytes.length > maximumBytes) {
                    throw new ResourceTooLargeException(name, maximumBytes);
                }
                return bytes.clone();
            }
        };
    }
}
