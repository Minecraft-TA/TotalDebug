package com.github.minecraft_ta.totalDebugCompanion.itemrender.integration.fusion;

import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemModelId;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemRenderException;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.UnsupportedItemModelException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Set;

/** Archive-side support for the parts of Fusion that have a deterministic isolated item representation. */
public final class FusionItemRenderIntegration {

    public static final String MODEL_LOADER = "fusion:model";

    private static final Set<String> CONNECTING_TEXTURE_PROPERTIES = Set.of(
            "type",
            "render_type",
            "emissive",
            "tinting",
            "layout",
            "connections",
            "sub_texture",
            "per_tile_animation"
    );

    private FusionItemRenderIntegration() {
    }

    public static void validateIsolatedModel(ItemModelId modelId, JsonObject json) throws UnsupportedItemModelException {
        String type = namespaced(requiredString(json, "type"));
        if (!type.equals("fusion:connecting")) {
            throw new UnsupportedItemModelException(modelId, "Fusion model type " + type);
        }
        if (json.has("materials")) {
            throw new UnsupportedItemModelException(modelId, "Fusion model materials");
        }
    }

    public static BufferedImage isolatedTexture(ItemModelId textureId, BufferedImage image, JsonObject metadata)
            throws ItemRenderException {
        try {
            JsonObject fusion = requiredObject(metadata, "fusion");
            String type = namespaced(requiredString(fusion, "type"));
            if (!type.equals("fusion:connecting")) {
                throw unsupported(textureId, "Fusion texture type " + type);
            }
            for (String property : fusion.keySet()) {
                if (!CONNECTING_TEXTURE_PROPERTIES.contains(property)) {
                    throw unsupported(textureId, "Fusion connecting texture property " + property);
                }
            }
            if (optionalBoolean(fusion, "emissive", false)) {
                throw unsupported(textureId, "emissive Fusion connecting texture");
            }
            if (fusion.has("tinting") && !fusion.get("tinting").isJsonNull()) {
                throw unsupported(textureId, "tinted Fusion connecting texture");
            }
            if (fusion.has("sub_texture") && !fusion.get("sub_texture").isJsonNull()) {
                throw unsupported(textureId, "nested Fusion connecting texture");
            }
            if (optionalBoolean(fusion, "per_tile_animation", false)) {
                throw unsupported(textureId, "per-tile animated Fusion connecting texture");
            }

            String layoutName = fusion.has("layout") ? requiredString(fusion, "layout") : "full";
            Layout layout = parseLayout(textureId, layoutName);
            if (layout.discardItemQuad) {
                return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            }
            return selectIsolatedTile(textureId, image, metadata, layout);
        } catch (ItemRenderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidMetadata(textureId, exception);
        }
    }

    private static BufferedImage selectIsolatedTile(
            ItemModelId textureId,
            BufferedImage image,
            JsonObject metadata,
            Layout layout
    ) throws ItemRenderException {
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        int effectiveImageHeight = imageHeight;
        int frameWidth = imageWidth;
        int frameHeight = imageHeight;
        JsonObject animation = metadata.has("animation") ? requiredObject(metadata, "animation") : null;

        if (layout == Layout.FULL && imageWidth == imageHeight) {
            if (animation != null) {
                throw invalidMetadata(textureId, "square legacy full layout cannot be animated");
            }
            frameHeight = imageHeight * 6 / 8;
            effectiveImageHeight = frameHeight;
        } else if (animation != null) {
            int declaredWidth = optionalInt(animation, "width", -1);
            int declaredHeight = optionalInt(animation, "height", -1);
            if (declaredWidth < 1 && declaredHeight < 1) {
                int tileSize = Math.min(imageWidth / layout.width, imageHeight / layout.height);
                frameWidth = layout.width * tileSize;
                frameHeight = layout.height * tileSize;
            } else {
                if (declaredWidth > 0) {
                    frameWidth = declaredWidth;
                }
                if (declaredHeight > 0) {
                    frameHeight = declaredHeight;
                }
            }
        }

        if (frameWidth < 1 || frameHeight < 1
                || imageWidth % frameWidth != 0
                || effectiveImageHeight % frameHeight != 0) {
            throw invalidMetadata(
                    textureId,
                    "image " + imageWidth + "x" + imageHeight + " does not contain whole animation frames"
            );
        }
        if (frameWidth % layout.width != 0 || frameHeight % layout.height != 0) {
            throw unsupported(textureId, "fractional Fusion " + layout.serializedName + " texture tile");
        }

        int columns = imageWidth / frameWidth;
        int rows = effectiveImageHeight / frameHeight;
        int frameIndex = animation == null ? 0 : firstFrameIndex(animation);
        if (frameIndex < 0 || frameIndex >= columns * rows) {
            throw invalidMetadata(textureId, "animation frame index is outside the texture");
        }

        int tileWidth = frameWidth / layout.width;
        int tileHeight = frameHeight / layout.height;
        int x = frameIndex % columns * frameWidth + layout.defaultTileX * tileWidth;
        int y = frameIndex / columns * frameHeight + layout.defaultTileY * tileHeight;
        return copy(image, x, y, tileWidth, tileHeight);
    }

    private static int firstFrameIndex(JsonObject animation) {
        if (!animation.has("frames")) {
            return 0;
        }
        JsonElement framesElement = animation.get("frames");
        if (!framesElement.isJsonArray()) {
            throw new JsonParseException("animation frames must be an array");
        }
        JsonArray frames = framesElement.getAsJsonArray();
        if (frames.isEmpty()) {
            return 0;
        }
        JsonElement first = frames.get(0);
        if (first.isJsonObject()) {
            return requiredInt(first.getAsJsonObject(), "index");
        }
        return first.getAsInt();
    }

    private static Layout parseLayout(ItemModelId textureId, String name) throws ItemRenderException {
        String normalized = name.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "full" -> Layout.FULL;
            case "horizontal" -> Layout.HORIZONTAL;
            case "simple" -> Layout.SIMPLE;
            case "vertical" -> Layout.VERTICAL;
            case "compact" -> Layout.COMPACT;
            case "pieced" -> Layout.PIECED;
            case "overlay" -> Layout.OVERLAY;
            default -> throw unsupported(textureId, "Fusion connecting texture layout " + name);
        };
    }

    private static BufferedImage copy(BufferedImage source, int x, int y, int width, int height) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = source.getRGB(x, y, width, height, null, 0, width);
        result.setRGB(0, 0, width, height, pixels, 0, width);
        return result;
    }

    private static JsonObject requiredObject(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new JsonParseException(name + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(name + " must be a string");
        }
        return value.getAsString();
    }

    private static int requiredInt(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(name + " must be an integer");
        }
        return value.getAsInt();
    }

    private static int optionalInt(JsonObject json, String name, int fallback) {
        return json.has(name) ? requiredInt(json, name) : fallback;
    }

    private static boolean optionalBoolean(JsonObject json, String name, boolean fallback) {
        if (!json.has(name)) {
            return fallback;
        }
        JsonElement value = json.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException(name + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static String namespaced(String identifier) {
        return identifier.indexOf(':') < 0 ? "fusion:" + identifier : identifier;
    }

    private static ItemRenderException unsupported(ItemModelId textureId, String feature) {
        return new ItemRenderException(
                ItemRenderException.Kind.UNSUPPORTED_FEATURE,
                feature,
                "Texture " + textureId + " requires unsupported feature: " + feature
        );
    }

    private static ItemRenderException invalidMetadata(ItemModelId textureId, RuntimeException cause) {
        return new ItemRenderException(
                ItemRenderException.Kind.RESOURCE_ERROR,
                "invalid Fusion texture metadata",
                "Invalid Fusion texture metadata for " + textureId + ": " + cause.getMessage(),
                cause
        );
    }

    private static ItemRenderException invalidMetadata(ItemModelId textureId, String message) {
        return new ItemRenderException(
                ItemRenderException.Kind.RESOURCE_ERROR,
                "invalid Fusion texture metadata",
                "Invalid Fusion texture metadata for " + textureId + ": " + message
        );
    }

    private enum Layout {
        FULL("full", 8, 6, 0, 0, false),
        HORIZONTAL("horizontal", 4, 1, 0, 0, false),
        SIMPLE("simple", 4, 4, 0, 0, false),
        VERTICAL("vertical", 1, 4, 0, 0, false),
        COMPACT("compact", 5, 1, 0, 0, false),
        PIECED("pieced", 5, 1, 0, 0, false),
        OVERLAY("overlay", 6, 3, 1, 1, true);

        private final String serializedName;
        private final int width;
        private final int height;
        private final int defaultTileX;
        private final int defaultTileY;
        private final boolean discardItemQuad;

        Layout(
                String serializedName,
                int width,
                int height,
                int defaultTileX,
                int defaultTileY,
                boolean discardItemQuad
        ) {
            this.serializedName = serializedName;
            this.width = width;
            this.height = height;
            this.defaultTileX = defaultTileX;
            this.defaultTileY = defaultTileY;
            this.discardItemQuad = discardItemQuad;
        }
    }
}
