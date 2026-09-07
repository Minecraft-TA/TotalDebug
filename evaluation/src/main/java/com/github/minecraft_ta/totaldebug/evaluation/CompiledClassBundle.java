package com.github.minecraft_ta.totaldebug.evaluation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Class definitions exchanged with the preloaded paused-evaluation bridge. */
public final class CompiledClassBundle {
    private static final int MAX_CLASSES = 256;
    private static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;

    private CompiledClassBundle() {
    }

    public static String encode(Map<String, byte[]> classes) throws IOException {
        validateCount(classes.size());
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(classes.size());
            for (var entry : classes.entrySet()) {
                validateLength(entry.getValue().length);
                output.writeUTF(entry.getKey());
                output.writeInt(entry.getValue().length);
                output.write(entry.getValue());
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    public static Map<String, byte[]> decode(String encoded) throws IOException {
        Map<String, byte[]> definitions = new LinkedHashMap<>();
        try (var input = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            int count = input.readInt();
            validateCount(count);
            for (int index = 0; index < count; index++) {
                String name = input.readUTF();
                int length = input.readInt();
                validateLength(length);
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length || definitions.putIfAbsent(name, bytes) != null) {
                    throw new IOException("Invalid evaluation class bundle");
                }
            }
            if (input.read() != -1) throw new IOException("Trailing evaluation class data");
        }
        return definitions;
    }

    private static void validateCount(int count) throws IOException {
        if (count < 1 || count > MAX_CLASSES) throw new IOException("Invalid evaluation class count: " + count);
    }

    private static void validateLength(int length) throws IOException {
        if (length < 1 || length > MAX_CLASS_BYTES) throw new IOException("Invalid evaluation class size");
    }
}
