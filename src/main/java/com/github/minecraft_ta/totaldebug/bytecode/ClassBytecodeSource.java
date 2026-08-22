package com.github.minecraft_ta.totaldebug.bytecode;

import java.io.IOException;

@FunctionalInterface
public interface ClassBytecodeSource {
    /**
     * Finds a class file by binary name, internal name, or class-file resource name.
     *
     * @return the class bytes, or {@code null} when the class cannot be found
     */
    byte[] findClassBytes(String className) throws IOException;
}
