package com.github.minecraft_ta.totaldebug.client.decompile;

import java.util.Objects;

public record DecompilationRequest(Class<?> targetClass, SourceTarget sourceTarget) {
    public DecompilationRequest {
        Objects.requireNonNull(targetClass, "targetClass");
        Objects.requireNonNull(sourceTarget, "sourceTarget");
    }
}
