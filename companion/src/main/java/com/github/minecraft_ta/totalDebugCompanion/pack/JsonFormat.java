package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Parses the JSON of packs and lays it out one value per line, indented by two spaces, as packs usually are. Keys keep
 * their order, numbers and text are written as they were, and only strict JSON without a key twice in one object is laid
 * out: comments, other leniencies and all but one of the duplicated keys would be lost, so such text is refused.
 */
public final class JsonFormat {
    private static final TypeAdapter<JsonElement> ELEMENTS = new Gson().getAdapter(JsonElement.class);
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonFormat() {
    }

    /** Whether the resource at {@code path} is JSON: a {@code .json} or {@code .mcmeta} file. */
    public static boolean formats(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".json") || lower.endsWith(".mcmeta");
    }

    /** {@code text} laid out, ending in a line break where it did; the problem is thrown as an argument error. */
    public static String format(String text) {
        JsonElement parsed = parse(text, Strictness.STRICT);
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setStrictness(Strictness.STRICT);
            refuseDuplicates(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Text follows the JSON value" + at(reader));
        } catch (IOException invalid) {
            throw new IllegalArgumentException(problem(invalid), invalid);
        }
        String formatted = PRETTY.toJson(parsed);
        // Written as it is, a lone surrogate, such as an escaped D800, would become ? in the saved UTF-8.
        for (int index = 0; index < formatted.length(); index++) {
            char unit = formatted.charAt(index);
            if (Character.isHighSurrogate(unit) && index + 1 < formatted.length()
                    && Character.isLowSurrogate(formatted.charAt(index + 1))) {
                index++;
            } else if (Character.isSurrogate(unit)) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "A text holds \\u%04X without its pair, which UTF-8 cannot store", (int) unit));
            }
        }
        return text.endsWith("\n") ? formatted + "\n" : formatted;
    }

    /**
     * The value {@code text} starts with, read with {@code strictness} as the game reads it, which ignores what follows;
     * the problem, with its line and column when the parser names them, is thrown as an argument error.
     */
    static JsonElement parse(String text, Strictness strictness) {
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setStrictness(strictness);
            return ELEMENTS.read(reader);
        } catch (IOException | JsonParseException | IllegalStateException invalid) {
            throw new IllegalArgumentException(problem(invalid), invalid);
        }
    }

    /** Reads one value, refusing an object that holds a key twice. */
    private static void refuseDuplicates(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> keys = new HashSet<>();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (!keys.add(key)) throw new IllegalArgumentException("\"" + key + "\" appears twice in one object" + at(reader));
                    refuseDuplicates(reader);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) refuseDuplicates(reader);
                reader.endArray();
            }
            default -> reader.skipValue();
        }
    }

    /** Where {@code reader} is, such as " at line 3 column 7". */
    private static String at(JsonReader reader) {
        return reader.toString().replaceFirst("^JsonReader", "").replaceFirst(" path \\S*$", "");
    }

    /** The parser's problem with its line and column, without its advice to programmers. */
    private static String problem(Exception invalid) {
        Throwable cause = invalid instanceof JsonParseException && invalid.getCause() != null ? invalid.getCause() : invalid;
        String message = String.valueOf(cause.getMessage());
        int advice = message.indexOf("\nSee ");
        if (advice >= 0) message = message.substring(0, advice);
        message = message.replaceFirst("^Use JsonReader\\.setStrictness\\(Strictness\\.LENIENT\\) to accept malformed JSON",
                "Malformed JSON");
        return message.replaceFirst(" path \\S*$", "");
    }
}
