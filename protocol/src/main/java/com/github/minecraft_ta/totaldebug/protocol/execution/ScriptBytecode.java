package com.github.minecraft_ta.totaldebug.protocol.execution;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The entry point and all classes emitted for one script, including nested classes. */
public record ScriptBytecode(String primaryClass, Map<String, byte[]> classes) {
    public static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_CLASSES = 256;

    public ScriptBytecode {
        Objects.requireNonNull(primaryClass, "primaryClass");
        classes = Map.copyOf(classes);
        if (classes.isEmpty() || classes.size() > MAX_CLASSES || !classes.containsKey(primaryClass)) {
            throw new IllegalArgumentException("Invalid script class bundle or missing primary class");
        }
        long size = 8L + primaryClass.getBytes(StandardCharsets.UTF_8).length;
        for (var entry : classes.entrySet()) {
            if (entry.getKey().isBlank() || entry.getValue().length == 0) {
                throw new IllegalArgumentException("Empty script class name or bytecode");
            }
            size += 8L + entry.getKey().getBytes(StandardCharsets.UTF_8).length + entry.getValue().length;
        }
        if (size > MAX_BYTES) throw new IllegalArgumentException("Compiled script exceeds " + MAX_BYTES + " bytes");
    }

    public static ScriptBytecode read(ByteBufferInputStream input) {
        String primaryClass = input.readString();
        int count = input.readInt();
        if (count < 1 || count > MAX_CLASSES) throw new IllegalArgumentException("Invalid script class count");
        var classes = new LinkedHashMap<String, byte[]>();
        long size = 8L + primaryClass.getBytes(StandardCharsets.UTF_8).length;
        for (int i = 0; i < count; i++) {
            String name = input.readString();
            int length = input.readInt();
            size += 8L + name.getBytes(StandardCharsets.UTF_8).length + length;
            if (length < 1 || size > MAX_BYTES) throw new IllegalArgumentException("Invalid compiled script size");
            if (classes.putIfAbsent(name, input.readByteArray(length)) != null) {
                throw new IllegalArgumentException("Duplicate script class: " + name);
            }
        }
        return new ScriptBytecode(primaryClass, classes);
    }

    public void write(ByteBufferOutputStream output) {
        output.writeString(this.primaryClass);
        output.writeInt(this.classes.size());
        for (var entry : this.classes.entrySet()) {
            output.writeString(entry.getKey());
            output.writeInt(entry.getValue().length);
            output.writeByteArray(entry.getValue());
        }
    }
}
