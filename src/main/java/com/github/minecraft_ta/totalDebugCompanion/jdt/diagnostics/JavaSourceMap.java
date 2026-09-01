package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

/** Maps offsets between text shown in an editor and its generated Java compilation unit. */
public interface JavaSourceMap {
    JavaSourceMap IDENTITY = new JavaSourceMap() {
        @Override
        public int toGeneratedOffset(int editorOffset) {
            return editorOffset;
        }

        @Override
        public int toEditorOffset(int generatedOffset) {
            return generatedOffset;
        }
    };

    /** Returns {@code -1} when the editor offset has no generated counterpart. */
    int toGeneratedOffset(int editorOffset);

    /** Returns {@code -1} when the generated offset belongs to hidden wrapper code. */
    int toEditorOffset(int generatedOffset);
}
