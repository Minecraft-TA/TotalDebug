package com.github.minecraft_ta.totaldebug.bytecode;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Reads class files from the target class loader and its runtime fallbacks.
 *
 * <p>This returns the class-file resource supplied by the loader. It is enough
 * for the first decompile flow, but it does not claim to reproduce bytes after
 * every runtime transformer has run.</p>
 */
public final class ClassLoaderBytecodeSource implements ClassBytecodeSource {
    private final Class<?> targetClass;
    private final List<ClassLoader> classLoaders;

    private ClassLoaderBytecodeSource(Class<?> targetClass) {
        this.targetClass = Objects.requireNonNull(targetClass, "targetClass");

        var loaders = new LinkedHashSet<ClassLoader>();
        addIfPresent(loaders, targetClass.getClassLoader());
        addIfPresent(loaders, Thread.currentThread().getContextClassLoader());
        addIfPresent(loaders, ClassLoaderBytecodeSource.class.getClassLoader());
        addIfPresent(loaders, ClassLoader.getSystemClassLoader());
        this.classLoaders = List.copyOf(loaders);
    }

    public static ClassLoaderBytecodeSource forClass(Class<?> targetClass) {
        return new ClassLoaderBytecodeSource(targetClass);
    }

    @Override
    public byte[] findClassBytes(String className) throws IOException {
        String resourceName = toResourceName(className);

        if (resourceName.equals(toResourceName(this.targetClass.getName()))) {
            try (InputStream stream = this.targetClass.getResourceAsStream('/' + resourceName)) {
                if (stream != null) {
                    return stream.readAllBytes();
                }
            }
        }

        for (ClassLoader classLoader : this.classLoaders) {
            try (InputStream stream = classLoader.getResourceAsStream(resourceName)) {
                if (stream != null) {
                    return stream.readAllBytes();
                }
            }
        }

        try (InputStream stream = ClassLoader.getSystemResourceAsStream(resourceName)) {
            return stream == null ? null : stream.readAllBytes();
        }
    }

    static String toResourceName(String className) {
        Objects.requireNonNull(className, "className");
        String normalized = className;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length());
        }
        return normalized.replace('.', '/') + ".class";
    }

    private static void addIfPresent(LinkedHashSet<ClassLoader> classLoaders, ClassLoader classLoader) {
        if (classLoader != null) {
            classLoaders.add(classLoader);
        }
    }
}
