package com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysisFixtures;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaEditorSource;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.awt.Font;
import static org.junit.jupiter.api.Assertions.*;

class JavaSemanticStylesTest {
    @Test void distinguishesConstantsFromFieldsWithoutTreatingFinalLocalsAsConstants() throws Exception {
        String text = "class Proof { static final int VALUE = 1; int field; int read(int parameter) { final int local = parameter; return VALUE + field + local; } }";
        try (var index = JavaAnalysisFixtures.index()) {
            CompanionClassIndex.set(index);
            var analysis = JavaAnalysis.parse("Proof", text, JavaEditorSource.identity(text), 1, index);
            assertEquals(ShadowedTokenTypes.TYPE, analysis.tokens().get(text.indexOf("Proof")));
            assertEquals(ShadowedTokenTypes.CONSTANT, analysis.tokens().get(text.lastIndexOf("VALUE")));
            assertEquals(ShadowedTokenTypes.FIELD, analysis.tokens().get(text.lastIndexOf("field")));
            assertEquals(TokenTypes.FUNCTION, analysis.tokens().get(text.indexOf("read")));
            assertNull(analysis.tokens().get(text.lastIndexOf("local")));
        } finally { CompanionClassIndex.clear(); }
    }

    @Test void riderFontStylesSurviveFontSizeAndThemeChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var area = new RSyntaxTextArea();
            area.setFont(new Font("JetBrains Mono", Font.PLAIN, 14));
            for (var palette : new EditorPalette[]{EditorPalette.islandsDark(), EditorPalette.islandsLight()}) {
                CodeUtils.initJavaColors(area.getSyntaxScheme(), palette);
                area.setFont(area.getFont().deriveFont(19f));
                var scheme = area.getSyntaxScheme();
                assertEquals(palette.classReference(), scheme.getStyle(ShadowedTokenTypes.TYPE).foreground);
                assertNotEquals(palette.identifier(), scheme.getStyle(ShadowedTokenTypes.TYPE).foreground);
                assertEquals(palette.field(), scheme.getStyle(ShadowedTokenTypes.CONSTANT).foreground);
                assertTrue(scheme.getStyle(ShadowedTokenTypes.CONSTANT).font.isBold());
                assertEquals(19, scheme.getStyle(ShadowedTokenTypes.CONSTANT).font.getSize());
                assertEquals(area.getFont().getFamily(), scheme.getStyle(ShadowedTokenTypes.CONSTANT).font.getFamily());
                assertTrue(scheme.getStyle(TokenTypes.COMMENT_EOL).font.isItalic());
                assertTrue(scheme.getStyle(TokenTypes.COMMENT_DOCUMENTATION).font.isItalic());
                assertEquals(Font.PLAIN, scheme.getStyle(TokenTypes.RESERVED_WORD).font.getStyle());
                assertEquals(Font.PLAIN, scheme.getStyle(ShadowedTokenTypes.FIELD).font.getStyle());
            }
        });
    }
}
