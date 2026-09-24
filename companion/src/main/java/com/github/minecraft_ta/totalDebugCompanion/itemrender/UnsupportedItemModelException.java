package com.github.minecraft_ta.totalDebugCompanion.itemrender;

/** Identifies a model feature that requires Minecraft or a mod-specific implementation. */
public final class UnsupportedItemModelException extends ItemRenderException {

    public UnsupportedItemModelException(ItemModelId modelId, String feature) {
        super(
                Kind.UNSUPPORTED_FEATURE,
                feature,
                "Item model " + modelId + " requires unsupported feature: " + feature
        );
    }
}
