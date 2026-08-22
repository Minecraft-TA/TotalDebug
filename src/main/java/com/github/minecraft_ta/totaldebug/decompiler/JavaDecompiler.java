package com.github.minecraft_ta.totaldebug.decompiler;

import com.github.minecraft_ta.totaldebug.bytecode.ClassBytecodeSource;

@FunctionalInterface
public interface JavaDecompiler {
    DecompilationResult decompile(String binaryName, ClassBytecodeSource bytecodeSource)
            throws DecompilationException;
}
