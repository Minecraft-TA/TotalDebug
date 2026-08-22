package com.github.minecraft_ta.totaldebug.script;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Defines all classes produced by one script compilation. */
public final class ScriptClassLoader extends ClassLoader {
    private final Map<String, byte[]> definitions;

    public ScriptClassLoader(ClassLoader parent, Map<String, byte[]> definitions) {
        super(Objects.requireNonNull(parent, "parent"));
        Objects.requireNonNull(definitions, "definitions");
        Map<String, byte[]> copiedDefinitions = new LinkedHashMap<>();
        definitions.forEach((name, bytes) -> copiedDefinitions.put(
                Objects.requireNonNull(name, "class name"),
                Objects.requireNonNull(bytes, "class bytes").clone()
        ));
        this.definitions = Map.copyOf(copiedDefinitions);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = this.definitions.get(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }
}
