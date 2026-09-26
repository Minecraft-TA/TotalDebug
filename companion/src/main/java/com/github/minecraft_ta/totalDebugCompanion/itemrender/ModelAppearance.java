package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The files and textures a block or item is drawn with: its blockstate, the models it names, and the textures those
 * models use after following parents and texture variables. Custom model loaders are named, not resolved.
 */
public record ModelAppearance(List<File> files, List<Texture> textures, List<String> loaders) {
    private static final int MAXIMUM_JSON_BYTES = 1024 * 1024;
    private static final int MAXIMUM_TEXTURE_BYTES = 4 * 1024 * 1024;
    private static final long MAXIMUM_TEXTURE_PIXELS = 16L * 1024 * 1024;
    private static final int MAXIMUM_MODELS = 8;
    private static final int MAXIMUM_TEXTURES = 24;
    private static final int MAXIMUM_PARENTS = 32;

    /** A blockstate or model file; {@code root} holds its effective copy. */
    public record File(String role, String resourcePath, ItemRenderResourceRoot root) {
    }

    /** A texture with the variable names that use it, such as {@code side} and {@code top}, and its first frame. */
    public record Texture(String id, List<String> variables, String resourcePath, ItemRenderResourceRoot root,
                          BufferedImage image) {
    }

    public ModelAppearance {
        files = List.copyOf(files);
        textures = List.copyOf(textures);
        loaders = List.copyOf(loaders);
    }

    public boolean isEmpty() {
        return this.files.isEmpty() && this.textures.isEmpty();
    }

    /** Resolves a block's blockstate models and an item's model; either id may be empty. */
    static ModelAppearance resolve(ResourcePackStack resources, String blockId, String itemModel) throws IOException {
        List<File> files = new ArrayList<>();
        Set<String> models = new LinkedHashSet<>();
        if (!blockId.isEmpty()) {
            String blockstate = "assets/" + namespace(blockId) + "/blockstates/" + path(blockId) + ".json";
            Optional<JsonObject> json = json(resources, blockstate);
            if (json.isPresent()) {
                files.add(new File("Blockstate", blockstate, resources.provider(blockstate).orElseThrow()));
                blockstateModels(json.get(), models);
            }
        }
        List<String> modelFiles = new ArrayList<>();
        for (String model : models) {
            if (modelFiles.size() >= MAXIMUM_MODELS) break;
            modelFiles.add(model);
        }
        if (!itemModel.isEmpty() && !modelFiles.contains(qualified(itemModel))) modelFiles.add(qualified(itemModel));

        Map<String, List<String>> variablesByTexture = new LinkedHashMap<>();
        Set<String> loaders = new LinkedHashSet<>();
        for (String model : modelFiles) {
            String resourcePath = modelPath(model);
            Optional<ItemRenderResourceRoot> root = resources.provider(resourcePath);
            if (root.isEmpty()) continue;
            files.add(new File(model.equals(qualified(itemModel)) && !models.contains(model) ? "Item model" : "Block model",
                    resourcePath, root.get()));
            Map<String, String> textures = new LinkedHashMap<>();
            walk(resources, model, textures, loaders);
            for (Map.Entry<String, String> variable : textures.entrySet()) {
                String texture = resolveVariable(variable.getValue(), textures);
                if (texture == null) continue;
                List<String> names = variablesByTexture.computeIfAbsent(qualified(texture), ignored -> new ArrayList<>());
                if (!names.contains(variable.getKey())) names.add(variable.getKey());
            }
        }

        List<Texture> textures = new ArrayList<>();
        for (Map.Entry<String, List<String>> texture : variablesByTexture.entrySet()) {
            if (textures.size() >= MAXIMUM_TEXTURES) break;
            String resourcePath = "assets/" + namespace(texture.getKey()) + "/textures/" + path(texture.getKey()) + ".png";
            Optional<ItemRenderResourceRoot> root = resources.provider(resourcePath);
            if (root.isEmpty()) continue;
            textures.add(new Texture(texture.getKey(), texture.getValue(), resourcePath, root.get(),
                    image(resources, resourcePath)));
        }
        return new ModelAppearance(files, textures, List.copyOf(loaders));
    }

    private static void blockstateModels(JsonObject blockstate, Set<String> models) {
        if (blockstate.has("variants") && blockstate.get("variants").isJsonObject()) {
            for (Map.Entry<String, JsonElement> variant : blockstate.getAsJsonObject("variants").entrySet()) {
                modelsOf(variant.getValue(), models);
            }
        }
        if (blockstate.has("multipart") && blockstate.get("multipart").isJsonArray()) {
            for (JsonElement part : blockstate.getAsJsonArray("multipart")) {
                if (part.isJsonObject() && part.getAsJsonObject().has("apply")) {
                    modelsOf(part.getAsJsonObject().get("apply"), models);
                }
            }
        }
    }

    private static void modelsOf(JsonElement value, Set<String> models) {
        if (value.isJsonArray()) {
            for (JsonElement option : value.getAsJsonArray()) modelsOf(option, models);
        } else if (value.isJsonObject() && value.getAsJsonObject().has("model")) {
            models.add(qualified(value.getAsJsonObject().get("model").getAsString()));
        }
    }

    /** Collects texture variables from a model and its parents; a child's value wins over its parent's. */
    private static void walk(ResourcePackStack resources, String model, Map<String, String> textures, Set<String> loaders)
            throws IOException {
        String current = model;
        for (int depth = 0; depth < MAXIMUM_PARENTS && current != null; depth++) {
            if (current.startsWith("minecraft:builtin/")) return;
            Optional<JsonObject> json = json(resources, modelPath(current));
            if (json.isEmpty()) return;
            JsonObject object = json.get();
            if (object.has("loader") && object.get("loader").isJsonPrimitive()) loaders.add(object.get("loader").getAsString());
            if (object.has("textures") && object.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> texture : object.getAsJsonObject("textures").entrySet()) {
                    if (texture.getValue().isJsonPrimitive()) {
                        textures.putIfAbsent(texture.getKey(), texture.getValue().getAsString());
                    }
                }
            }
            current = object.has("parent") && object.get("parent").isJsonPrimitive()
                    ? qualified(object.get("parent").getAsString()) : null;
        }
    }

    /** Follows {@code #variable} references; null for a reference that never reaches a texture. */
    static String resolveVariable(String value, Map<String, String> textures) {
        String current = value;
        for (int depth = 0; depth < MAXIMUM_PARENTS && current != null; depth++) {
            if (!current.startsWith("#")) return current;
            current = textures.get(current.substring(1));
        }
        return null;
    }

    private static Optional<JsonObject> json(ResourcePackStack resources, String resourcePath) throws IOException {
        Optional<byte[]> bytes = resources.read(resourcePath, MAXIMUM_JSON_BYTES);
        if (bytes.isEmpty()) return Optional.empty();
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes.get(), StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? Optional.of(parsed.getAsJsonObject()) : Optional.empty();
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static BufferedImage image(ResourcePackStack resources, String resourcePath) throws IOException {
        Optional<byte[]> bytes = resources.read(resourcePath, MAXIMUM_TEXTURE_BYTES);
        if (bytes.isEmpty()) return null;
        BufferedImage image;
        try {
            image = TextureImages.decode(bytes.get(), MAXIMUM_TEXTURE_PIXELS);
        } catch (TextureImages.TooLarge tooLarge) {
            return null;
        }
        return image == null ? null : TextureAnimation.firstFrameOfStrip(image);
    }

    private static String modelPath(String model) {
        return "assets/" + namespace(model) + "/models/" + path(model) + ".json";
    }

    static String qualified(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    private static String namespace(String id) {
        return qualified(id).substring(0, qualified(id).indexOf(':'));
    }

    private static String path(String id) {
        return qualified(id).substring(qualified(id).indexOf(':') + 1);
    }
}
