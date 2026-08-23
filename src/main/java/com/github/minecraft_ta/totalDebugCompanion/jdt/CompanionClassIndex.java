package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.tth05.jindex.ClassIndex;

import java.nio.file.Path;
import java.util.Objects;

public final class CompanionClassIndex {
    private static volatile ClassIndex classIndex;

    private CompanionClassIndex() {
    }

    public static synchronized void open(Path indexFile) {
        ensureUninitialized();
        classIndex = ClassIndex.fromFile(Objects.requireNonNull(indexFile, "indexFile").toString());
    }

    public static synchronized void replace(Path indexFile) {
        ClassIndex replacement = ClassIndex.fromFile(Objects.requireNonNull(indexFile, "indexFile").toString());
        ClassIndex previous = classIndex;
        classIndex = replacement;
        if (previous != null) {
            previous.close();
        }
    }

    public static boolean isOpen() {
        return classIndex != null;
    }

    static synchronized void initialize(ClassIndex index) {
        ensureUninitialized();
        classIndex = Objects.requireNonNull(index, "index");
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

    private static void ensureUninitialized() {
        if (classIndex != null) {
            throw new IllegalStateException("Companion class index is already initialized");
        }
    }
}
