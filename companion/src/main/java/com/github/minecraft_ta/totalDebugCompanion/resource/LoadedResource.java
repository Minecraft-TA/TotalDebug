package com.github.minecraft_ta.totalDebugCompanion.resource;

import com.github.minecraft_ta.totalDebugCompanion.itemrender.TextureAnimation;

import java.awt.image.BufferedImage;

public sealed interface LoadedResource permits LoadedResource.Text, LoadedResource.Image {

    int byteCount();

    record Text(String value, String syntaxStyle, String charsetName, int byteCount) implements LoadedResource {
    }

    /**
     * A decoded image. {@code animation} is the texture animation from its {@code .mcmeta}, or null; when that file
     * exists but cannot be used, {@code animationProblem} says why.
     */
    record Image(BufferedImage value, int byteCount, TextureAnimation animation, String animationProblem)
            implements LoadedResource {
        public Image(BufferedImage value, int byteCount) {
            this(value, byteCount, null, "");
        }
    }
}
