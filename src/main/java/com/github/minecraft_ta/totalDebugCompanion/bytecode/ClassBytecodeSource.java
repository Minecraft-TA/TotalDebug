package com.github.minecraft_ta.totalDebugCompanion.bytecode;

import java.io.IOException;

@FunctionalInterface
public interface ClassBytecodeSource {
    /**
     * Returns whether this source contains the requested class.
     */
    default boolean hasClass(String className) throws IOException {
        return findClassBytes(className) != null;
    }

    /**
     * Finds a class file by binary name, internal name, or class-file resource name.
     *
     * @return the class bytes, or {@code null} when the class cannot be found
     */
    byte[] findClassBytes(String className) throws IOException;
}
