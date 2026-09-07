package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The archive-backed inputs required to render one resolved item model.
 * Tint values use Minecraft's {@code 0xAARRGGBB} convention and are keyed by tint index.
 */
public record ItemRenderRequest(ItemModelId modelId, int size, Map<Integer, Integer> tintColors) {

    public static final int MAXIMUM_SIZE = 512;

    public ItemRenderRequest {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(tintColors, "tintColors");
        if (size < 1 || size > MAXIMUM_SIZE) {
            throw new IllegalArgumentException("Item render size must be between 1 and " + MAXIMUM_SIZE + ": " + size);
        }

        TreeMap<Integer, Integer> copiedTints = new TreeMap<>();
        for (Map.Entry<Integer, Integer> entry : tintColors.entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 0) {
                throw new IllegalArgumentException("Tint indices must be non-negative");
            }
            copiedTints.put(entry.getKey(), Objects.requireNonNull(entry.getValue(), "tint color"));
        }
        tintColors = Collections.unmodifiableMap(copiedTints);
    }

    public ItemRenderRequest(ItemModelId modelId, int size) {
        this(modelId, size, Map.of());
    }

    public static ItemRenderRequest of(String modelId, int size) {
        return new ItemRenderRequest(ItemModelId.parse(modelId), size);
    }

    int tintColor(int tintIndex) {
        return tintIndex < 0 ? 0xFFFFFFFF : this.tintColors.getOrDefault(tintIndex, 0xFFFFFFFF);
    }
}
