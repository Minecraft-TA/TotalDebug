package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Captured client fluid properties. Textures still come from the supplied resource packs. */
record FluidAppearance(ItemModelId stillTexture, int tint, int lightLevel, boolean lighterThanAir) {
    static final String RESOURCE_PATH = "totaldebug/fluid-appearances.json";

    FluidAppearance {
        Objects.requireNonNull(stillTexture, "stillTexture");
        if (lightLevel < 0 || lightLevel > 15) {
            throw new IllegalArgumentException("Fluid light level must be between 0 and 15");
        }
    }

    static Map<ItemModelId, FluidAppearance> read(ResourcePackStack resources) throws IOException {
        Map<ItemModelId, FluidAppearance> result = new HashMap<>();
        try {
            for (byte[] bytes : resources.readStack(RESOURCE_PATH, 4 * 1024 * 1024)) {
                JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.get("schemaVersion").getAsInt() != 1) {
                    throw new IllegalArgumentException("Expected fluid appearance schemaVersion 1");
                }
                for (var entry : root.getAsJsonObject("fluids").entrySet()) {
                    JsonObject fluid = entry.getValue().getAsJsonObject();
                    String tint = fluid.get("tint").getAsString();
                    if (!tint.matches("[0-9a-fA-F]{8}")) {
                        throw new IllegalArgumentException("Fluid tint must contain eight ARGB hexadecimal digits");
                    }
                    result.put(ItemModelId.parse(entry.getKey()), new FluidAppearance(
                            ItemModelId.parse(fluid.get("stillTexture").getAsString()),
                            (int) Long.parseLong(tint, 16), fluid.get("lightLevel").getAsInt(),
                            fluid.get("lighterThanAir").getAsBoolean()));
                }
            }
        } catch (RuntimeException exception) {
            throw new ItemRenderException(ItemRenderException.Kind.RESOURCE_ERROR, "invalid fluid appearance capture",
                    "Invalid " + RESOURCE_PATH + ": " + exception.getMessage(), exception);
        }
        return Map.copyOf(result);
    }
}
