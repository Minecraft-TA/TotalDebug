package com.github.minecraft_ta.totalDebugCompanion.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptViewSourceTest {
    @Test
    void newEditorScriptUsesTheObjectReturnContract() {
        String source = ScriptView.initialSource("ProofScript");

        assertTrue(source.contains("public Object run() throws Throwable"));
        assertTrue(source.contains("return null;"));
        assertFalse(source.contains("public void run()"));
    }
}
