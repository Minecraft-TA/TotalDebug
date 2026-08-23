package com.github.minecraft_ta.totalDebugCompanion.ui.theme;

import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.ShadowedTokenTypes;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorPaletteTest {

    @Test
    void padsColoursThatDropLeadingZeroes() {
        // IntelliJ colour schemes write 0033b3 as "33b3"; decoding without padding silently yields
        // the wrong colour rather than failing.
        assertEquals(new Color(0x0033B3), EditorPalette.hex("33b3"));
        assertEquals(new Color(0x00627A), EditorPalette.hex("627a"));
        assertEquals(new Color(0x067D17), EditorPalette.hex("67d17"));
        assertEquals(new Color(0x080808), EditorPalette.hex("80808"));
        assertEquals(new Color(0xBCBEC4), EditorPalette.hex("BCBEC4"));
        assertEquals(new Color(0xBCBEC4), EditorPalette.hex("#BCBEC4"));
    }

    @Test
    void rejectsOversizedColourValues() {
        assertThrows(IllegalArgumentException.class, () -> EditorPalette.hex("1234567"));
    }

    @Test
    void mapsPaletteOntoSyntaxScheme() {
        EditorPalette palette = EditorPalette.islandsDark();
        SyntaxScheme scheme = new SyntaxScheme(true);

        CodeUtils.initJavaColors(scheme, palette);

        assertEquals(palette.keyword(), scheme.getStyle(TokenTypes.RESERVED_WORD).foreground);
        assertEquals(palette.keyword(), scheme.getStyle(TokenTypes.LITERAL_BOOLEAN).foreground);
        assertEquals(palette.string(), scheme.getStyle(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE).foreground);
        assertEquals(palette.number(), scheme.getStyle(TokenTypes.LITERAL_NUMBER_DECIMAL_INT).foreground);
        assertEquals(palette.comment(), scheme.getStyle(TokenTypes.COMMENT_EOL).foreground);
        assertEquals(palette.docComment(), scheme.getStyle(TokenTypes.COMMENT_DOCUMENTATION).foreground);
        assertEquals(palette.annotation(), scheme.getStyle(TokenTypes.ANNOTATION).foreground);
        assertEquals(palette.instanceMethod(), scheme.getStyle(TokenTypes.FUNCTION).foreground);
        // the two semantic types produced by CustomJavaTokenMaker
        assertEquals(palette.classReference(), scheme.getStyle(ShadowedTokenTypes.TYPE).foreground);
        assertEquals(palette.field(), scheme.getStyle(ShadowedTokenTypes.FIELD).foreground);
    }

    @Test
    void bothPalettesAreFullyPopulated() {
        for (EditorPalette palette : new EditorPalette[]{EditorPalette.islandsDark(), EditorPalette.islandsLight()}) {
            for (var component : EditorPalette.class.getRecordComponents()) {
                try {
                    assertEquals(Color.class, component.getType(), component.getName());
                    Object value = component.getAccessor().invoke(palette);
                    org.junit.jupiter.api.Assertions.assertNotNull(value, component.getName());
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError(exception);
                }
            }
        }
    }
}
