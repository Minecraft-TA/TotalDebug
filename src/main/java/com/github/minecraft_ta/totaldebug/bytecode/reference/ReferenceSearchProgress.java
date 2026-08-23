package com.github.minecraft_ta.totaldebug.bytecode.reference;

/** Immutable phase progress reported after each processed class file. */
public record ReferenceSearchProgress(
        ReferenceSearchPhase phase,
        int processedClassFiles,
        int totalClassFiles
) {
    public ReferenceSearchProgress {
        java.util.Objects.requireNonNull(phase, "phase");
        if (processedClassFiles < 0 || totalClassFiles < 0 || processedClassFiles > totalClassFiles) {
            throw new IllegalArgumentException(
                    "Invalid reference-search progress: " + processedClassFiles + "/" + totalClassFiles
            );
        }
    }
}
