package com.github.minecraft_ta.totalDebugCompanion.util;

import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.ShadowedTokenTypes;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

public class CodeUtils {

    /** Applies the active theme's editor colours to {@code scheme}. */
    public static void initJavaColors(SyntaxScheme scheme) {
        initJavaColors(scheme, ThemeManager.palette());
    }

    /**
     * Maps an {@link EditorPalette} onto RSyntaxTextArea's token types.
     *
     * <p>Most tokens are produced by {@code CustomJavaTokenMaker} from resolved JDT bindings rather
     * than by RSyntaxTextArea's own Java lexer, which is why the semantic types
     * ({@link ShadowedTokenTypes#TYPE}, {@link ShadowedTokenTypes#FIELD}) matter as much as the
     * lexical ones.
     */
    public static void initJavaColors(SyntaxScheme scheme, EditorPalette palette) {
        initSyntaxColors(scheme, palette);
        initJavaSemanticColors(scheme, palette);
    }

    public static void initSyntaxColors(SyntaxScheme scheme, EditorPalette palette) {
        scheme.getStyle(TokenTypes.RESERVED_WORD).foreground = palette.keyword();
        scheme.getStyle(TokenTypes.RESERVED_WORD_2).foreground = palette.keyword();
        scheme.getStyle(TokenTypes.DATA_TYPE).foreground = palette.keyword();
        scheme.getStyle(TokenTypes.PREPROCESSOR).foreground = palette.keyword();
        // IntelliJ renders true/false as keywords rather than as literals.
        scheme.getStyle(TokenTypes.LITERAL_BOOLEAN).foreground = palette.keyword();

        scheme.getStyle(TokenTypes.VARIABLE).foreground = palette.foreground();

        scheme.getStyle(TokenTypes.LITERAL_NUMBER_DECIMAL_INT).foreground = palette.number();
        scheme.getStyle(TokenTypes.LITERAL_NUMBER_FLOAT).foreground = palette.number();
        scheme.getStyle(TokenTypes.LITERAL_NUMBER_HEXADECIMAL).foreground = palette.number();

        scheme.getStyle(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE).foreground = palette.string();
        scheme.getStyle(TokenTypes.LITERAL_CHAR).foreground = palette.string();
        scheme.getStyle(TokenTypes.LITERAL_BACKQUOTE).foreground = palette.string();
        scheme.getStyle(TokenTypes.REGEX).foreground = palette.string();

        scheme.getStyle(TokenTypes.COMMENT_EOL).foreground = palette.comment();
        scheme.getStyle(TokenTypes.COMMENT_MULTILINE).foreground = palette.comment();
        scheme.getStyle(TokenTypes.COMMENT_MARKUP).foreground = palette.comment();
        scheme.getStyle(TokenTypes.COMMENT_DOCUMENTATION).foreground = palette.docComment();
        scheme.getStyle(TokenTypes.COMMENT_KEYWORD).foreground = palette.docComment();
        scheme.getStyle(TokenTypes.MARKUP_COMMENT).foreground = palette.comment();

        scheme.getStyle(TokenTypes.SEPARATOR).foreground = palette.foreground();
        scheme.getStyle(TokenTypes.OPERATOR).foreground = palette.foreground();
        scheme.getStyle(TokenTypes.IDENTIFIER).foreground = palette.foreground();

        scheme.getStyle(TokenTypes.ANNOTATION).foreground = palette.annotation();
        scheme.getStyle(TokenTypes.FUNCTION).foreground = palette.instanceMethod();

        scheme.getStyle(TokenTypes.MARKUP_TAG_DELIMITER).foreground = palette.foreground();
        scheme.getStyle(TokenTypes.MARKUP_TAG_NAME).foreground = palette.keyword();
        scheme.getStyle(TokenTypes.MARKUP_TAG_ATTRIBUTE).foreground = palette.field();
        scheme.getStyle(TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE).foreground = palette.string();
        scheme.getStyle(TokenTypes.MARKUP_DTD).foreground = palette.keyword();
        scheme.getStyle(TokenTypes.MARKUP_PROCESSING_INSTRUCTION).foreground = palette.keyword();
        scheme.getStyle(TokenTypes.MARKUP_CDATA_DELIMITER).foreground = palette.keyword();
        scheme.getStyle(TokenTypes.MARKUP_CDATA).foreground = palette.string();
        scheme.getStyle(TokenTypes.MARKUP_ENTITY_REFERENCE).foreground = palette.annotation();
    }

    public static void initJavaSemanticColors(SyntaxScheme scheme, EditorPalette palette) {
        scheme.getStyle(ShadowedTokenTypes.TYPE).foreground = palette.classReference();
        scheme.getStyle(ShadowedTokenTypes.FIELD).foreground = palette.field();
    }

    public static void initSyntaxScheme(RSyntaxTextArea component) {
        component.setSyntaxEditingStyle(RSyntaxTextArea.SYNTAX_STYLE_JAVA);
        initJavaColors(component.getSyntaxScheme());
    }

    public static String[] splitTypeName(String typeStr) {
        var endIndex = typeStr.lastIndexOf('/');
        if (endIndex == -1)
            endIndex = typeStr.lastIndexOf('.');
        if (endIndex == -1)
            return new String[]{"", typeStr};

        var packageName = typeStr.substring(0, endIndex);
        var typeName = typeStr.substring(packageName.length() + 1);
        return new String[]{packageName, typeName};
    }

}
