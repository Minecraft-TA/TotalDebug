package com.github.minecraft_ta.totalDebugCompanion.inspection;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads fluid textures from a resource snapshot: the captured appearance names each fluid's still texture and tint,
 * and the texture's first animation frame is tinted the way Minecraft draws a fluid in an inventory. It is drawn
 * opaque, so a translucent texture such as water keeps its colour on a light panel.
 */
final class FluidTextures {
    static final String APPEARANCES = "layers/0/totaldebug/fluid-appearances.json";

    private record Appearance(String stillTexture, int tint) {
    }

    private final Path archive;
    private final Map<String, Appearance> appearances;

    private FluidTextures(Path archive, Map<String, Appearance> appearances) {
        this.archive = archive;
        this.appearances = appearances;
    }

    static FluidTextures open(Path archive) throws IOException {
        Map<String, Appearance> appearances = new HashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(APPEARANCES);
            if (entry != null) {
                try (InputStream input = zip.getInputStream(entry)) {
                    JsonObject root = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    for (var fluid : root.getAsJsonObject("fluids").entrySet()) {
                        JsonObject value = fluid.getValue().getAsJsonObject();
                        appearances.put(fluid.getKey(), new Appearance(value.get("stillTexture").getAsString(),
                                (int) Long.parseLong(value.get("tint").getAsString(), 16)));
                    }
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid fluid appearances in " + archive, exception);
        }
        return new FluidTextures(archive, Map.copyOf(appearances));
    }

    /** The tinted first frame of the fluid's still texture, or empty when it was not captured. */
    Optional<BufferedImage> texture(String fluidId) throws IOException {
        Appearance appearance = this.appearances.get(fluidId);
        if (appearance == null) {
            return Optional.empty();
        }
        int separator = appearance.stillTexture().indexOf(':');
        String namespace = separator < 0 ? "minecraft" : appearance.stillTexture().substring(0, separator);
        String path = appearance.stillTexture().substring(separator + 1);
        try (ZipFile zip = new ZipFile(this.archive.toFile())) {
            ZipEntry entry = zip.getEntry("layers/0/assets/" + namespace + "/textures/" + path + ".png");
            if (entry == null) {
                return Optional.empty();
            }
            BufferedImage image;
            try (InputStream input = zip.getInputStream(entry)) {
                image = ImageIO.read(input);
            }
            return image == null ? Optional.empty() : Optional.of(tinted(firstFrame(image), appearance.tint()));
        }
    }

    /** Animated textures stack square frames vertically. */
    static BufferedImage firstFrame(BufferedImage image) {
        int size = Math.min(image.getWidth(), image.getHeight());
        return image.getSubimage(0, 0, image.getWidth(), size);
    }

    static BufferedImage tinted(BufferedImage source, int tint) {
        int tintAlpha = tint >>> 24;
        int tintRed = tint >> 16 & 0xFF;
        int tintGreen = tint >> 8 & 0xFF;
        int tintBlue = tint & 0xFF;
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int pixel = source.getRGB(x, y);
                int alpha = (pixel >>> 24) == 0 ? 0 : tintAlpha;
                int red = (pixel >> 16 & 0xFF) * tintRed / 255;
                int green = (pixel >> 8 & 0xFF) * tintGreen / 255;
                int blue = (pixel & 0xFF) * tintBlue / 255;
                result.setRGB(x, y, alpha << 24 | red << 16 | green << 8 | blue);
            }
        }
        return result;
    }
}
