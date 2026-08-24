package com.github.minecraft_ta.totalDebugCompanion.util;

import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.ShadowedTokenTypes;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import java.util.regex.Pattern;

public class CodeUtils {

    private static final Pattern GENERIC_PATTERN = Pattern.compile("T(\\w+);");
    private static final Pattern TYPE_PATTERN = Pattern.compile("[LQ][\\w/]*?/?([\\w$]+);");

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

    /**
     * Fix the identifier for a method. This removes the most amount of information possible from the given method
     * identifier without loosing any uniqueness. Can be used to make comparisons between method identifiers easier.
     *
     * @param key the original key
     * @return the fixed key
     */
    public static String minimalizeMethodIdentifier(String key, boolean keepGenerics) {
        var builder = new StringBuilder(key);

        // Remove exception data
        var exceptionIndex = builder.indexOf("|");
        if (exceptionIndex != -1)
            builder.delete(exceptionIndex, builder.length());

        // Remove useless super class data
        var percentIndex = builder.lastIndexOf("%");
        if (percentIndex != -1)
            builder.delete(percentIndex, builder.length());

        // Remove class name, class type parameters
        var dotIndex = builder.indexOf(".");
        if (dotIndex != -1 && dotIndex < builder.indexOf("("))
            builder.delete(0, dotIndex + 1);

        // Remove any type parameters
        var genericIndex = -1;
        while ((genericIndex = builder.indexOf("<")) != -1) {
            var endIndex = genericIndex + 1;
            var count = 1;
            //Find closing '>'
            for (; count != 0 && endIndex < builder.length(); endIndex++) {
                var c = builder.charAt(endIndex);
                if (c == '<')
                    count++;
                else if (c == '>')
                    count--;
            }
            if (count != 0 || endIndex == -1)
                break;

            builder.delete(genericIndex, endIndex);
        }

        var result = GENERIC_PATTERN.matcher(builder.toString()).replaceAll(keepGenerics ? "L$1;" : "Ljava/lang/Object;");
        result = TYPE_PATTERN.matcher(result).replaceAll("L$1;");
        return result;
    }
}
