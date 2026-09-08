package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemRenderBackend;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemRenderRequest;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemRenderResourceRoot;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemModelId;
import com.github.minecraft_ta.totaldebug.storage.GameCatalog;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

public record CatalogSnapshot(Path archive, GameCatalog catalog, long size, FileTime modified) {
    public record Inspection(BufferedImage preview, List<String> resources, List<String> notes) { }

    public boolean matches(BasicFileAttributes attributes) {
        return attributes.size() == size && attributes.lastModifiedTime().equals(modified);
    }

    public void checkCurrent() throws IOException {
        if (!matches(Files.readAttributes(archive, BasicFileAttributes.class)))
            throw new IOException("The capture changed. Refresh the explorer to use the new capture.");
    }

    public List<ItemRenderResourceRoot> roots() {
        int count = catalog.resources().values().stream().mapToInt(List::size).max().orElse(1);
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(layer -> ItemRenderResourceRoot.nested(archive, "layers/" + layer)).toList();
    }

    public byte[] read(String resource) throws IOException {
        checkCurrent();
        try (var zip = new ZipFile(archive.toFile())) {
            var entry = zip.getEntry(catalog.effectiveEntry(resource));
            if (entry == null || entry.getSize() > 64L * 1024 * 1024)
                throw new IOException("Missing or oversized resource: " + resource);
            try (var input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(64 * 1024 * 1024 + 1);
                if (bytes.length > 64 * 1024 * 1024) throw new IOException("Resource too large: " + resource);
                checkCurrent();
                return bytes;
            }
        }
    }

    public Inspection inspect(GameCatalog.Entry entry) throws IOException {
        checkCurrent();
        Set<String> resources = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        BufferedImage image = null;
        try (var backend = ItemRenderBackend.openResourceRoots(roots())) {
            if (entry.kind() == GameCatalog.Kind.BLOCK) {
                String blockstate = asset(entry.id(), "blockstates", ".json");
                visit(blockstate, backend, resources, notes);
            }
            if (entry.model().isEmpty()) {
                notes.add("No inventory model binding captured for this entry.");
            } else {
                visit(asset(entry.model(), "models", ".json"), backend, resources, notes);
                var rendered = backend.inspect(new ItemRenderRequest(ItemModelId.parse(entry.model()), 256, entry.tintColors()));
                image = rendered.image();
                resources.addAll(rendered.resources());
                if (rendered.failure() != null) notes.add("Preview unavailable: " + rendered.failure().getMessage());
            }
        }
        checkCurrent();
        notes.add("Preview uses the base inventory model and captured default-item colors. Other stack states, predicates and custom renderers may change its appearance.");
        return new Inspection(image, List.copyOf(resources), List.copyOf(notes));
    }

    private void visit(String path, ItemRenderBackend backend, Set<String> found, List<String> notes) throws IOException {
        if (found.size() >= 1024) throw new IOException("Model dependency graph exceeds 1024 resources");
        if (!found.add(path)) return;
        if (!catalog.resources().containsKey(path)) {
            notes.add("Resource not captured: " + path);
            return;
        }
        if (!path.endsWith(".json")) return;
        try {
            inspectJson(JsonParser.parseString(new String(read(path), StandardCharsets.UTF_8)), backend, found, notes);
        } catch (RuntimeException failure) {
            notes.add("Cannot inspect " + path + ": " + failure.getMessage());
        }
    }

    private void inspectJson(JsonElement json, ItemRenderBackend backend, Set<String> found, List<String> notes) throws IOException {
        if (json.isJsonArray()) {
            for (var child : json.getAsJsonArray()) inspectJson(child, backend, found, notes);
        } else if (json.isJsonObject()) {
            for (var member : json.getAsJsonObject().entrySet()) {
                String key = member.getKey();
                JsonElement value = member.getValue();
                if ((key.equals("parent") || key.equals("model")) && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isString()) {
                    String model = value.getAsString();
                    if (!ItemModelId.parse(model).path().startsWith("builtin/"))
                        visit(model.endsWith(".obj") ? asset(model, "", "") : asset(model, "models", ".json"), backend, found, notes);
                } else if (key.equals("textures") && value.isJsonObject()) {
                    for (var texture : value.getAsJsonObject().entrySet()) {
                        if (!texture.getValue().isJsonPrimitive() || !texture.getValue().getAsJsonPrimitive().isString()) continue;
                        String sprite = texture.getValue().getAsString();
                        if (sprite.startsWith("#")) continue;
                        try {
                            for (String texturePath : backend.textureResources(sprite)) {
                                found.add(texturePath);
                                if (catalog.resources().containsKey(texturePath + ".mcmeta")) found.add(texturePath + ".mcmeta");
                            }
                        } catch (IOException | RuntimeException failure) {
                            notes.add("Texture " + sprite + ": " + failure.getMessage());
                        }
                    }
                } else {
                    inspectJson(value, backend, found, notes);
                }
            }
        }
    }

    private static String asset(String id, String directory, String suffix) {
        var parsed = ItemModelId.parse(id);
        return "assets/" + parsed.namespace() + "/" + (directory.isEmpty() ? "" : directory + "/") + parsed.path() + suffix;
    }
}
