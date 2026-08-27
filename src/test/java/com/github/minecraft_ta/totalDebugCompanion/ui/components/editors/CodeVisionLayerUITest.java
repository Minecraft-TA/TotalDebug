package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeVisionLayerUITest {

    @Test
    void placesInlineValueAfterCodeVisionOnTheSameLine() {
        assertEquals(198, CodeVisionLayerUI.inlineValueStart(120, 180));
    }
}
