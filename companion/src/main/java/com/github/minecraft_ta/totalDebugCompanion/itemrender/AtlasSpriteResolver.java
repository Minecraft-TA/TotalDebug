package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Resolves the vanilla blocks atlas sources needed by archive-backed item models. */
final class AtlasSpriteResolver {

    private static final String ATLAS_PATH = "assets/minecraft/atlases/blocks.json";
    private static final int MAXIMUM_ATLAS_BYTES = 4 * 1024 * 1024;
    private static final int MAXIMUM_SOURCE_ENTRIES = 4096;
    private static final int MAXIMUM_PALETTE_PIXELS = 65536;

    private final ResourcePackStack resources;
    private List<JsonObject> sources;

    AtlasSpriteResolver(ResourcePackStack resources) {
        this.resources = resources;
    }

    Sprite resolve(ItemModelId spriteId) throws IOException {
        Sprite selected = null;
        boolean removed = false;
        Set<String> unsupported = new LinkedHashSet<>();
        try {
            for (JsonObject source : sources()) {
                String type = ItemModelId.parse(string(source, "type")).toString();
                Sprite candidate = null;
                switch (type) {
                    case "minecraft:paletted_permutations" -> candidate = permutation(source, spriteId);
                    case "minecraft:single" -> {
                        ItemModelId resource = ItemModelId.parse(string(source, "resource"));
                        ItemModelId id = source.has("sprite") ? ItemModelId.parse(string(source, "sprite")) : resource;
                        if (id.equals(spriteId)) {
                            candidate = new Sprite(resource, null, null);
                        }
                    }
                    case "minecraft:directory" -> {
                        String prefix = string(source, "prefix");
                        if (spriteId.path().startsWith(prefix) && spriteId.path().length() > prefix.length()) {
                            String path = string(source, "source") + "/" + spriteId.path().substring(prefix.length());
                            candidate = new Sprite(new ItemModelId(spriteId.namespace(), path), null, null);
                        }
                    }
                    case "minecraft:filter" -> {
                        JsonObject pattern = source.getAsJsonObject("pattern");
                        if (selected != null && matches(pattern, "namespace", spriteId.namespace())
                                && matches(pattern, "path", spriteId.path())) {
                            selected = null;
                            removed = true;
                        }
                    }
                    default -> unsupported.add(type);
                }
                // Vanilla only registers a single/directory/palette source when its base PNG exists.
                if (candidate != null && this.resources.contains(candidate.resource().textureResourcePath())) {
                    selected = candidate;
                    removed = false;
                }
            }
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException | NullPointerException exception) {
            throw new ItemRenderException(ItemRenderException.Kind.RESOURCE_ERROR, "invalid atlas definition",
                    "Invalid blocks atlas definition while resolving " + spriteId + ": " + exception.getMessage(), exception);
        }
        if (selected != null) {
            return selected;
        }
        if (removed) {
            throw new ItemRenderException(ItemRenderException.Kind.MISSING_RESOURCE, spriteId.toString(),
                    "Blocks atlas filters removed sprite " + spriteId);
        }
        // Keep direct model textures available outside the supported atlas declarations. Unknown
        // custom sources cannot be evaluated or assumed to replace an explicitly known sprite.
        if (this.resources.contains(spriteId.textureResourcePath())) {
            return new Sprite(spriteId, null, null);
        }
        throw new ItemRenderException(ItemRenderException.Kind.MISSING_RESOURCE, spriteId.textureResourcePath(),
                "Missing sprite " + spriteId + " in the supplied resources and supported blocks atlas sources"
                        + (unsupported.isEmpty() ? "" : "; unevaluated atlas source types: " + unsupported));
    }

    BufferedImage applyPalette(Sprite sprite, BufferedImage base, ImageLoader loader) throws IOException {
        BufferedImage key = loader.read(sprite.paletteKey());
        BufferedImage palette = loader.read(sprite.palette());
        long keySize = (long) key.getWidth() * key.getHeight();
        long paletteSize = (long) palette.getWidth() * palette.getHeight();
        if (keySize != paletteSize) {
            throw new ItemRenderException(ItemRenderException.Kind.RESOURCE_ERROR, "palette size mismatch",
                    "Palette " + sprite.palette() + " and key " + sprite.paletteKey() + " have different pixel counts: "
                            + paletteSize + " and " + keySize);
        }
        if (keySize > MAXIMUM_PALETTE_PIXELS) {
            throw new ItemRenderException(ItemRenderException.Kind.RESOURCE_ERROR, "palette pixel limit",
                    "Palette " + sprite.paletteKey() + " exceeds the " + MAXIMUM_PALETTE_PIXELS + " pixel limit");
        }
        int[] keys = key.getRGB(0, 0, key.getWidth(), key.getHeight(), null, 0, key.getWidth());
        int[] values = palette.getRGB(0, 0, palette.getWidth(), palette.getHeight(), null, 0, palette.getWidth());
        Map<Integer, Integer> mapping = new HashMap<>();
        for (int index = 0; index < keys.length; index++) {
            if ((keys[index] >>> 24) != 0) {
                mapping.put(keys[index] & 0xFFFFFF, values[index]);
            }
        }
        BufferedImage result = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < base.getHeight(); y++) {
            for (int x = 0; x < base.getWidth(); x++) {
                int pixel = base.getRGB(x, y);
                int alpha = pixel >>> 24;
                if (alpha != 0) {
                    int mapped = mapping.getOrDefault(pixel & 0xFFFFFF, pixel | 0xFF000000);
                    pixel = (alpha * (mapped >>> 24) / 255) << 24 | (mapped & 0xFFFFFF);
                }
                result.setRGB(x, y, pixel);
            }
        }
        return result;
    }

    void clearCache() {
        this.sources = null;
    }

    private List<JsonObject> sources() throws IOException {
        if (this.sources == null) {
            List<JsonObject> parsed = new ArrayList<>();
            for (byte[] resource : this.resources.readStack(ATLAS_PATH, MAXIMUM_ATLAS_BYTES)) {
                JsonObject json = JsonParser.parseString(new String(resource, StandardCharsets.UTF_8)).getAsJsonObject();
                for (JsonElement entry : json.getAsJsonArray("sources")) {
                    if (parsed.size() >= MAXIMUM_SOURCE_ENTRIES) {
                        throw new JsonParseException("atlas sources exceed the " + MAXIMUM_SOURCE_ENTRIES + " entry limit");
                    }
                    parsed.add(entry.getAsJsonObject());
                }
            }
            this.sources = List.copyOf(parsed);
        }
        return this.sources;
    }

    private static Sprite permutation(JsonObject source, ItemModelId spriteId) {
        JsonObject permutations = source.getAsJsonObject("permutations");
        var textures = source.getAsJsonArray("textures");
        if (permutations.size() > MAXIMUM_SOURCE_ENTRIES || textures.size() > MAXIMUM_SOURCE_ENTRIES
                || (long) permutations.size() * textures.size() > MAXIMUM_SOURCE_ENTRIES) {
            throw new JsonParseException("palette source exceeds the " + MAXIMUM_SOURCE_ENTRIES + " entry limit");
        }
        Sprite selected = null;
        for (JsonElement texture : textures) {
            ItemModelId base = ItemModelId.parse(texture.getAsString());
            for (Map.Entry<String, JsonElement> permutation : permutations.entrySet()) {
                String suffix = "_" + permutation.getKey();
                if (spriteId.namespace().equals(base.namespace()) && spriteId.path().equals(base.path() + suffix)) {
                    selected = new Sprite(base, ItemModelId.parse(string(source, "palette_key")),
                            ItemModelId.parse(permutation.getValue().getAsString()));
                }
            }
        }
        return selected;
    }

    private static boolean matches(JsonObject pattern, String key, String value) {
        return !pattern.has(key) || Pattern.compile(string(pattern, key)).matcher(value).find();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(key + " must be a string");
        }
        return value.getAsString();
    }

    record Sprite(ItemModelId resource, ItemModelId paletteKey, ItemModelId palette) {
        boolean generated() {
            return this.paletteKey != null;
        }
    }

    @FunctionalInterface
    interface ImageLoader {
        BufferedImage read(ItemModelId id) throws IOException;
    }
}
