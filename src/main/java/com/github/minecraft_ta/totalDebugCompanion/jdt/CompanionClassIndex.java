package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.tth05.jindex.ClassIndex;

import java.util.Objects;

public final class CompanionClassIndex {
    private static volatile ClassIndex classIndex;

    private CompanionClassIndex() {
    }

    public static synchronized void replace(ClassIndex replacement) {
        Objects.requireNonNull(replacement, "replacement");
        ClassIndex previous = classIndex;
        classIndex = replacement;
        if (previous != null) {
            previous.close();
        }
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

    public static synchronized void close() {
        ClassIndex index = classIndex;
        classIndex = null;
        if (index != null) {
            index.close();
        }
    }

}
