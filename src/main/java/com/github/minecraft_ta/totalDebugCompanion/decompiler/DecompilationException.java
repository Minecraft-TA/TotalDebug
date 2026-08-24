package com.github.minecraft_ta.totalDebugCompanion.decompiler;

import java.io.IOException;

public final class DecompilationException extends IOException {
    public DecompilationException(String message) {
        super(message);
    }

    public DecompilationException(String message, Throwable cause) {
        super(message, cause);
    }
}
