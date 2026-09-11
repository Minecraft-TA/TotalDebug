package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.tth05.jindex.ClassIndex;

import java.util.Objects;

/** Process-wide lookup hook for JDT. Installed runtime resources own the native index. */
public final class CompanionClassIndex {
    private static volatile ClassIndex classIndex;

    private CompanionClassIndex() {
    }

    public static void set(ClassIndex replacement) {
        classIndex = Objects.requireNonNull(replacement, "replacement");
    }

    public static boolean isOpen() {
        return classIndex != null;
    }

    public static ClassIndex get() {
        ClassIndex index = classIndex;
        if (index == null) {
            throw new IllegalStateException("Companion class index is not initialized");
        }
        return index;
    }

    public static void clear() {
        classIndex = null;
    }
}
