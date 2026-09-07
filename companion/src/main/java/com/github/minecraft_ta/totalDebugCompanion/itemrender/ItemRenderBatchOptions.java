package com.github.minecraft_ta.totalDebugCompanion.itemrender;

/** Controls whether a batch keeps successful images for its caller. */
public record ItemRenderBatchOptions(boolean retainImages) {

    public static final ItemRenderBatchOptions RETAIN_IMAGES = new ItemRenderBatchOptions(true);
    public static final ItemRenderBatchOptions DIAGNOSTICS = new ItemRenderBatchOptions(false);
}
