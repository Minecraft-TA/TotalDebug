package com.github.minecraft_ta.totalDebugCompanion.decompiler;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.ClassBytecodeSource;

@FunctionalInterface
public interface JavaDecompiler {
    DecompilationResult decompile(String binaryName, ClassBytecodeSource bytecodeSource)
            throws DecompilationException;
}
