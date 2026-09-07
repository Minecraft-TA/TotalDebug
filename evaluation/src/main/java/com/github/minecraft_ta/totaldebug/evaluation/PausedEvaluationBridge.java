package com.github.minecraft_ta.totaldebug.evaluation;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/** Preloaded entry point for installing Companion-compiled code on the selected JDI thread. */
public final class PausedEvaluationBridge {
    private PausedEvaluationBridge() { }

    public static void preload() {
        // Resolve our loading path while the VM is running, before any suspend-all breakpoint.
        Base64.getDecoder().decode("");
        CompiledClassBundle.class.getName();
        new ScriptClassLoader(PausedEvaluationBridge.class.getClassLoader(), Map.of());
    }

    public static Class<?> install(String encoded, String primaryName, Class<?> lexicalOwner)
            throws IOException, ClassNotFoundException {
        Map<String, byte[]> definitions = CompiledClassBundle.decode(encoded);
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
