package com.github.minecraft_ta.totaldebug.bytecode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Objects;

/**
 * Reads class files through the target class's defining loader.
 *
 * <p>This returns the class-file resource supplied by the loader. It is enough
 * for the first decompile flow, but it does not claim to reproduce bytes after
 * every runtime transformer has run.</p>
 */
public final class ClassLoaderBytecodeSource implements ClassBytecodeSource {
    private final Class<?> targetClass;
    private final ClassLoader definingClassLoader;

    private ClassLoaderBytecodeSource(Class<?> targetClass) {
        this.targetClass = Objects.requireNonNull(targetClass, "targetClass");
        this.definingClassLoader = targetClass.getClassLoader();
    }

    public static ClassLoaderBytecodeSource forClass(Class<?> targetClass) {
        return new ClassLoaderBytecodeSource(targetClass);
    }

    @Override
    public byte[] findClassBytes(String className) throws IOException {
        URL resource = findClassResource(className);
        if (resource == null) {
            return null;
        }
        try (InputStream stream = resource.openStream()) {
            return stream == null ? null : stream.readAllBytes();
        }
    }

    public URL findClassResource(String className) {
        String resourceName = toResourceName(className);
        Class<?> resourceOwner = resolveClass(resourceName);
        return resourceOwner == null ? null : resourceOwner.getResource('/' + resourceName);
    }

    public ClassLoader definingClassLoader() {
        return this.definingClassLoader;
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

    private Class<?> resolveClass(String resourceName) {
        String targetResourceName = toResourceName(this.targetClass.getName());
        if (targetResourceName.equals(resourceName)) {
            return this.targetClass;
        }

        String binaryName = resourceName.substring(0, resourceName.length() - ".class".length()).replace('/', '.');
        try {
            return Class.forName(binaryName, false, this.definingClassLoader);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }
}
