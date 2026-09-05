package com.github.minecraft_ta.totaldebug.evaluation;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Preloaded entry point for installing Companion-compiled code on the selected JDI thread. */
public final class PausedEvaluationBridge {
    private PausedEvaluationBridge() { }

    public static void preload() {
        // Resolve our loading path while the VM is running, before any suspend-all breakpoint.
        Base64.getDecoder().decode("");
        new ScriptClassLoader(PausedEvaluationBridge.class.getClassLoader(), Map.of());
    }

    public static Class<?> install(String encoded, String primaryName, Class<?> lexicalOwner)
            throws IOException, ClassNotFoundException {
        Map<String, byte[]> definitions = new LinkedHashMap<>();
        try (var input = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            int count = input.readInt();
            if (count < 1 || count > 256) throw new IOException("Invalid evaluation class count: " + count);
            for (int i = 0; i < count; i++) {
                String name = input.readUTF();
                int length = input.readInt();
                if (length < 1 || length > 16 * 1024 * 1024) throw new IOException("Invalid evaluation class size");
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length || definitions.putIfAbsent(name, bytes) != null) {
                    throw new IOException("Invalid evaluation class bundle");
                }
            }
            if (input.read() != -1) throw new IOException("Trailing evaluation class data");
        }
        ClassLoader parent = lexicalOwner.getClassLoader();
        if (parent == null) throw new IllegalArgumentException("Compiled evaluation requires an application class loader");
        ClassLoader loader = new ScriptClassLoader(parent, definitions);
        Class<?> compiled = Class.forName(primaryName, true, loader);
        for (var field : compiled.getDeclaredFields()) {
            Class<?> type = field.getType();
            while (type.isArray()) type = type.getComponentType();
            if (!type.isPrimitive()) Class.forName(type.getName(), false, loader);
        }
        return compiled;
    }
}
