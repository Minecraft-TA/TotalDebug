package com.github.minecraft_ta.totalDebugCompanion.resource;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class FileTypeResolverTest {

    @Test
    void resolvesRequiredResourceTypesCaseInsensitively() {
        assertText("neoforge.mods.TOML", FileTypeResolver.SYNTAX_STYLE_TOML);
        assertText("options.CFG", RSyntaxTextArea.SYNTAX_STYLE_INI);
        assertText("pack.MCMETA", RSyntaxTextArea.SYNTAX_STYLE_JSON);
        assertText("MANIFEST.MF", FileTypeResolver.SYNTAX_STYLE_MANIFEST);
        assertEquals(ResourceFileType.Kind.PNG, FileTypeResolver.resolve("texture.PNG").kind());
    }

    @Test
    void distinguishesClassesBinariesAndUnknownFiles() {
        assertEquals(ResourceFileType.Kind.CLASS, FileTypeResolver.resolve("Example.class").kind());
        assertSame(Icons.JAVA_CLASS, FileTypeResolver.resolve("package-info.class").icon());
        assertEquals(ResourceFileType.Kind.BINARY, FileTypeResolver.resolve("sound.ogg").kind());
        assertEquals(ResourceFileType.Kind.UNKNOWN, FileTypeResolver.resolve("fabric.accessWidener2").kind());
        assertSame(Icons.BINARY_FILE, FileTypeResolver.resolve("level.dat").icon());
    }

    private static void assertText(String name, String syntaxStyle) {
        ResourceFileType type = FileTypeResolver.resolve(name);
        assertEquals(ResourceFileType.Kind.TEXT, type.kind());
        assertEquals(syntaxStyle, type.syntaxStyle());
    }
}
