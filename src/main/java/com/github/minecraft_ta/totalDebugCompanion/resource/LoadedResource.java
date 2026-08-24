package com.github.minecraft_ta.totalDebugCompanion.resource;

import java.awt.image.BufferedImage;

public sealed interface LoadedResource permits LoadedResource.Text, LoadedResource.Image {

    int byteCount();

    record Text(String value, String syntaxStyle, String charsetName, int byteCount) implements LoadedResource {
    }

    record Image(BufferedImage value, int byteCount) implements LoadedResource {
    }
}
