package com.github.minecraft_ta.totaldebug.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Strict metadata reads and a single atomic JSON publication path. */
public final class JsonFiles {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonFiles() {
    }

    public static JsonObject read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement value = JsonParser.parseReader(reader);
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("Expected an object");
            }
            return value.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid JSON file " + path + ": " + exception.getMessage(), exception);
        }
    }

    public static void write(Path path, JsonElement value) throws IOException {
        AtomicFiles.writeString(path, GSON.toJson(value) + System.lineSeparator());
    }

    public static String string(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("Expected nonblank string: " + key);
        }
        return value.getAsString();
    }

    public static int integer(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Expected integer: " + key);
        }
        return value.getAsBigDecimal().intValueExact();
    }

    public static boolean bool(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Expected boolean: " + key);
        }
        return value.getAsBoolean();
    }

    public static JsonArray array(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException("Expected array: " + key);
        }
        return value.getAsJsonArray();
    }

    public static JsonObject object(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException("Expected object: " + key);
        }
        return value.getAsJsonObject();
    }

    private static JsonElement required(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return value;
    }
}
