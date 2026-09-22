package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.tth05.jindex.ClassIndex;

import java.util.Objects;

/** Process-wide lookup hook for JDT. Installed runtime resources own the native index. */
public final class CompanionClassIndex {
    private record RuntimeIndex(ClassIndex index, IndexedParameterNames parameterNames) { }
    private static volatile RuntimeIndex runtime;

    private CompanionClassIndex() {
    }

    public static void set(ClassIndex replacement) {
        runtime = new RuntimeIndex(Objects.requireNonNull(replacement, "replacement"), new IndexedParameterNames(replacement));
    }

    /** JIndex only accepts ASCII queries; unsupported names have no indexed match. */
    public static boolean supportsQuery(String name) {
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) > 0x7f) return false;
        return true;
    }

    public static boolean isOpen() {
        return runtime != null;
    }

    /** Identity of the installed analysis environment, independent of document revisions. */
    public static Object identity() { return runtime; }

    public static ClassIndex get() {
        return current().index();
    }

    public static String[] parameterNames(String owner, String name, String descriptor) {
        return current().parameterNames().resolve(owner, name, descriptor);
    }

    private static RuntimeIndex current() {
        RuntimeIndex index = runtime;
        if (index == null) {
            throw new IllegalStateException("Companion class index is not initialized");
        }
        return index;
    }

    public static void clear() {
        runtime = null;
    }
}
