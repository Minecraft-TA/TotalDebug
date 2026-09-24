package com.github.minecraft_ta.totaldebug.protocol.nbt;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * NBT decoded from Minecraft's binary form without Minecraft classes, so Companion can show, search and copy the exact
 * data a game read reported. {@link #snbt(Tag)} prints what Minecraft's own {@code Tag.toString()} prints for the same
 * data; {@link #path(List)} prints the path syntax {@code /data get} accepts.
 */
public final class NbtData {
    /** Minecraft's own nesting limit for NBT. */
    public static final int MAX_DEPTH = 512;
    private static final Pattern SIMPLE_KEY = Pattern.compile("[A-Za-z0-9._+-]+");

    private NbtData() {
    }

    /** One NBT value. Compounds keep their keys in Minecraft's printing order. */
    public sealed interface Tag permits ByteTag, ShortTag, IntTag, LongTag, FloatTag, DoubleTag, StringTag,
            ByteArrayTag, IntArrayTag, LongArrayTag, ListTag, CompoundTag {
        /** The type's name as shown to players, e.g. {@code int} or {@code compound}. */
        String typeName();
    }

    public record ByteTag(byte value) implements Tag {
        public String typeName() { return "byte"; }
    }

    public record ShortTag(short value) implements Tag {
        public String typeName() { return "short"; }
    }

    public record IntTag(int value) implements Tag {
        public String typeName() { return "int"; }
    }

    public record LongTag(long value) implements Tag {
        public String typeName() { return "long"; }
    }

    public record FloatTag(float value) implements Tag {
        public String typeName() { return "float"; }
    }

    public record DoubleTag(double value) implements Tag {
        public String typeName() { return "double"; }
    }

    public record StringTag(String value) implements Tag {
        public StringTag {
            Objects.requireNonNull(value, "value");
        }

        public String typeName() { return "string"; }
    }

    public record ByteArrayTag(List<Byte> values) implements Tag {
        public ByteArrayTag {
            values = List.copyOf(values);
        }

        public String typeName() { return "byte[]"; }
    }

    public record IntArrayTag(List<Integer> values) implements Tag {
        public IntArrayTag {
            values = List.copyOf(values);
        }

        public String typeName() { return "int[]"; }
    }

    public record LongArrayTag(List<Long> values) implements Tag {
        public LongArrayTag {
            values = List.copyOf(values);
        }

        public String typeName() { return "long[]"; }
    }

    public record ListTag(List<Tag> items) implements Tag {
        public ListTag {
            items = List.copyOf(items);
        }

        public String typeName() { return "list"; }
    }

    /** A compound whose entries are sorted by key, as Minecraft prints them. */
    public record CompoundTag(Map<String, Tag> entries) implements Tag {
        public CompoundTag {
            List<String> keys = new ArrayList<>(entries.keySet());
            Collections.sort(keys);
            Map<String, Tag> sorted = new LinkedHashMap<>();
            for (String key : keys) {
                sorted.put(key, Objects.requireNonNull(entries.get(key), key));
            }
            entries = Collections.unmodifiableMap(sorted);
        }

        public String typeName() { return "compound"; }
    }

    /**
     * Reads one unnamed tag as written by Minecraft's {@code NbtIo.writeAnyTag}: its type id followed by its payload.
     *
     * @throws IllegalArgumentException when the bytes are not exactly one well-formed tag
     */
    public static Tag read(byte[] bytes) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            Tag tag = payload(input, input.readByte(), 0);
            if (input.read() != -1) {
                throw new IllegalArgumentException("Trailing bytes after the NBT tag");
            }
            return tag;
        } catch (EOFException exception) {
            throw new IllegalArgumentException("The NBT data ends early", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unreadable NBT data: " + exception.getMessage(), exception);
        }
    }

    private static Tag payload(DataInputStream input, byte type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("NBT nests deeper than " + MAX_DEPTH);
        }
        return switch (type) {
            case 1 -> new ByteTag(input.readByte());
            case 2 -> new ShortTag(input.readShort());
            case 3 -> new IntTag(input.readInt());
            case 4 -> new LongTag(input.readLong());
            case 5 -> new FloatTag(input.readFloat());
            case 6 -> new DoubleTag(input.readDouble());
            case 7 -> {
                int length = length(input);
                List<Byte> values = new ArrayList<>(Math.min(length, 1_024));
                for (int index = 0; index < length; index++) values.add(input.readByte());
                yield new ByteArrayTag(values);
            }
            case 8 -> new StringTag(input.readUTF());
            case 9 -> {
                byte elementType = input.readByte();
                int length = length(input);
                if (elementType == 0 && length > 0) {
                    throw new IllegalArgumentException("A list of end tags is not empty");
                }
                List<Tag> items = new ArrayList<>(Math.min(length, 1_024));
                for (int index = 0; index < length; index++) items.add(payload(input, elementType, depth + 1));
                yield new ListTag(items);
            }
            case 10 -> {
                Map<String, Tag> entries = new LinkedHashMap<>();
                while (true) {
                    byte entryType = input.readByte();
                    if (entryType == 0) break;
                    String key = input.readUTF();
                    entries.put(key, payload(input, entryType, depth + 1));
                }
                yield new CompoundTag(entries);
            }
            case 11 -> {
                int length = length(input);
                List<Integer> values = new ArrayList<>(Math.min(length, 1_024));
                for (int index = 0; index < length; index++) values.add(input.readInt());
                yield new IntArrayTag(values);
            }
            case 12 -> {
                int length = length(input);
                List<Long> values = new ArrayList<>(Math.min(length, 1_024));
                for (int index = 0; index < length; index++) values.add(input.readLong());
                yield new LongArrayTag(values);
            }
            default -> throw new IllegalArgumentException("Unknown NBT tag type " + type);
        };
    }

    private static int length(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > input.available()) {
            throw new IllegalArgumentException("Invalid NBT length " + length);
        }
        return length;
    }

    /** The SNBT Minecraft prints for this tag with {@code Tag.toString()}. */
    public static String snbt(Tag tag) {
        StringBuilder builder = new StringBuilder();
        write(builder, tag, null, 0);
        return builder.toString();
    }

    /** SNBT with one entry per line, indented by two spaces; it parses to the same data as {@link #snbt(Tag)}. */
    public static String prettySnbt(Tag tag) {
        StringBuilder builder = new StringBuilder();
        write(builder, tag, "  ", 0);
        return builder.toString();
    }

    private static void write(StringBuilder builder, Tag tag, String indent, int level) {
        switch (tag) {
            case ByteTag value -> builder.append(value.value()).append('b');
            case ShortTag value -> builder.append(value.value()).append('s');
            case IntTag value -> builder.append(value.value());
            case LongTag value -> builder.append(value.value()).append('L');
            case FloatTag value -> builder.append(value.value()).append('f');
            case DoubleTag value -> builder.append(value.value()).append('d');
            case StringTag value -> builder.append(quote(value.value()));
            case ByteArrayTag array -> numbers(builder, "[B;", array.values(), "B", indent);
            case IntArrayTag array -> numbers(builder, "[I;", array.values(), "", indent);
            case LongArrayTag array -> numbers(builder, "[L;", array.values(), "L", indent);
            case ListTag list -> {
                builder.append('[');
                for (int index = 0; index < list.items().size(); index++) {
                    if (index > 0) builder.append(',');
                    newline(builder, indent, level + 1);
                    write(builder, list.items().get(index), indent, level + 1);
                }
                if (!list.items().isEmpty()) newline(builder, indent, level);
                builder.append(']');
            }
            case CompoundTag compound -> {
                builder.append('{');
                boolean first = true;
                for (Map.Entry<String, Tag> entry : compound.entries().entrySet()) {
                    if (!first) builder.append(',');
                    first = false;
                    newline(builder, indent, level + 1);
                    builder.append(key(entry.getKey())).append(':');
                    if (indent != null) builder.append(' ');
                    write(builder, entry.getValue(), indent, level + 1);
                }
                if (!compound.entries().isEmpty()) newline(builder, indent, level);
                builder.append('}');
            }
        }
    }

    private static void numbers(StringBuilder builder, String prefix, List<? extends Number> values, String suffix,
                                String indent) {
        builder.append(prefix);
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) builder.append(',');
            if (indent != null) builder.append(' ');
            builder.append(values.get(index)).append(suffix);
        }
        builder.append(']');
    }

    private static void newline(StringBuilder builder, String indent, int level) {
        if (indent == null) return;
        builder.append('\n');
        builder.append(indent.repeat(level));
    }

    /** A compound key as Minecraft prints it: bare when simple, quoted otherwise. */
    public static String key(String key) {
        return SIMPLE_KEY.matcher(key).matches() ? key : quote(key);
    }

    /**
     * Minecraft's string quoting: double quotes, or single quotes when the text's first quote character is a double
     * quote. Only backslashes and the chosen quote character are escaped.
     */
    public static String quote(String text) {
        StringBuilder builder = new StringBuilder(" ");
        char quote = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\\') {
                builder.append('\\');
            } else if (character == '"' || character == '\'') {
                if (quote == 0) {
                    quote = character == '"' ? '\'' : '"';
                }
                if (quote == character) {
                    builder.append('\\');
                }
            }
            builder.append(character);
        }
        if (quote == 0) {
            quote = '"';
        }
        builder.setCharAt(0, quote);
        return builder.append(quote).toString();
    }

    /**
     * A path to a value in the syntax of Minecraft's NBT path argument, e.g. {@code Items[0].components} or
     * {@code "odd key".value}. Segments are compound keys ({@link String}) or list and array indexes
     * ({@link Integer}); an empty path is the root.
     */
    public static String path(List<?> segments) {
        StringBuilder builder = new StringBuilder();
        for (Object segment : segments) {
            switch (segment) {
                case Integer index -> builder.append('[').append(index).append(']');
                case String key -> {
                    if (!builder.isEmpty()) builder.append('.');
                    builder.append(pathKey(key));
                }
                default -> throw new IllegalArgumentException("Path segments are keys or indexes: " + segment);
            }
        }
        return builder.toString();
    }

    private static String pathKey(String key) {
        boolean bare = !key.isEmpty();
        for (int index = 0; index < key.length() && bare; index++) {
            char character = key.charAt(index);
            bare = !Character.isWhitespace(character) && "\"'[]{}.".indexOf(character) < 0;
        }
        if (bare) {
            return key;
        }
        return '"' + key.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
