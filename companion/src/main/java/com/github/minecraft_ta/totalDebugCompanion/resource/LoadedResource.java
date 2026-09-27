package com.github.minecraft_ta.totalDebugCompanion.resource;

import com.github.minecraft_ta.totalDebugCompanion.itemrender.TextureAnimation;

import java.awt.image.BufferedImage;

public sealed interface LoadedResource permits LoadedResource.Text, LoadedResource.Image {

    int byteCount();

    record Text(String value, String syntaxStyle, String charsetName, int byteCount) implements LoadedResource {
    }

    /**
     * A decoded image. {@code animation} is the texture animation from its {@code .mcmeta}, or null; when that file
     * exists but cannot be used, {@code animationProblem} says why. {@code metadata} is that file as it was read beside
     * the image, or null without one.
     */
    record Image(BufferedImage value, int byteCount, TextureAnimation animation, String animationProblem, byte[] metadata)
            implements LoadedResource {
        public Image(BufferedImage value, int byteCount) {
            this(value, byteCount, null, "", null);
        }

        public Image(BufferedImage value, int byteCount, TextureAnimation animation, String animationProblem) {
            this(value, byteCount, animation, animationProblem, null);
        }
    }
}
