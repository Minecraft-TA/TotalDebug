package com.github.minecraft_ta.totalDebugCompanion.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptViewSourceTest {
    @Test
    void newEditorScriptStartsAsAnUnwrappedSnippet() {
        String source = ScriptView.initialSource("ProofScript");

        assertEquals("", source);
    }
}
