package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.syntax.ManifestTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.syntax.TomlTokenMaker;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.modes.IniTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.JsonTokenMaker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TokenMakerRegistrationTest {

    @Test
    void customMappingsDoNotReplaceBuiltInLexers() {
        CompanionApp.configureTokenMakers();
        TokenMakerFactory factory = TokenMakerFactory.getDefaultInstance();

        assertInstanceOf(CustomJavaTokenMaker.class, factory.getTokenMaker(RSyntaxTextArea.SYNTAX_STYLE_JAVA));
        assertInstanceOf(JsonTokenMaker.class, factory.getTokenMaker(RSyntaxTextArea.SYNTAX_STYLE_JSON));
        assertInstanceOf(IniTokenMaker.class, factory.getTokenMaker(RSyntaxTextArea.SYNTAX_STYLE_INI));
        assertInstanceOf(TomlTokenMaker.class, factory.getTokenMaker(FileTypeResolver.SYNTAX_STYLE_TOML));
        assertInstanceOf(ManifestTokenMaker.class, factory.getTokenMaker(FileTypeResolver.SYNTAX_STYLE_MANIFEST));
    }
}
