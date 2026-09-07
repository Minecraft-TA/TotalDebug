package com.github.minecraft_ta.totalDebugCompanion.ui.theme;

import java.awt.Color;

/**
 * The code editor half of a theme.
 *
 * <p>IntelliJ keeps editor colours in a separate {@code .xml} colour scheme rather than in the
 * {@code .theme.json}, and those schemes inherit from a base scheme ({@code Darcula} / {@code
 * Default}) using attribute names that mean nothing to RSyntaxTextArea. Rather than ship a general
 * IntelliJ colour-scheme parser to recover roughly twenty values, the values are lifted here once.
 *
 * <p>Sources: {@code themes/islands/IslandSchemeDark.xml} and {@code themes/expUI/expUI_lightScheme.xml}
 * from {@code intellij.platform.ide.impl.jar} (Apache-2.0).
 *
 * @param background        editor and gutter background ({@code TEXT} background)
 * @param foreground        default text ({@code TEXT} foreground)
 * @param keyword           {@code DEFAULT_KEYWORD}
 * @param string            {@code DEFAULT_STRING}
 * @param number            {@code DEFAULT_NUMBER}
 * @param comment           {@code DEFAULT_LINE_COMMENT} / {@code DEFAULT_BLOCK_COMMENT}
 * @param docComment        {@code DEFAULT_DOC_COMMENT} (rendered italic)
 * @param methodDeclaration {@code DEFAULT_FUNCTION_DECLARATION}
 * @param instanceMethod    {@code DEFAULT_INSTANCE_METHOD}
 * @param field             {@code DEFAULT_INSTANCE_FIELD} / {@code DEFAULT_STATIC_FIELD} / {@code DEFAULT_CONSTANT}
 * @param annotation        {@code DEFAULT_METADATA}
 * @param classReference    {@code DEFAULT_CLASS_REFERENCE}
 * @param caret             {@code CARET_COLOR}
 * @param currentLine       {@code CARET_ROW_COLOR}
 * @param breakpointLine    debugger breakpoint row background
 * @param executionLine     selected debugger frame row background
 * @param lineNumber        {@code LINE_NUMBERS_COLOR}
 * @param indentGuide       {@code INDENT_GUIDE}
 * @param matchedBracket    background behind a matched bracket pair
 * @param hyperlink         ctrl-click link colour
 */
public record EditorPalette(
        Color background,
        Color foreground,
        Color keyword,
        Color string,
        Color number,
        Color comment,
        Color docComment,
        Color methodDeclaration,
        Color instanceMethod,
        Color field,
        Color annotation,
        Color classReference,
        Color caret,
        Color currentLine,
        Color breakpointLine,
        Color executionLine,
        Color lineNumber,
        Color indentGuide,
        Color matchedBracket,
        Color hyperlink
) {

    public static EditorPalette islandsDark() {
        return new EditorPalette(
                hex("191A1C"),  // background
                hex("BCBEC4"),  // foreground
                hex("CF8E6D"),  // keyword
                hex("6AAB73"),  // string
                hex("2AACB8"),  // number
                hex("7A7E85"),  // comment
                hex("5F826B"),  // docComment
                hex("56A8F5"),  // methodDeclaration
                hex("57AAF7"),  // instanceMethod
                hex("C77DBB"),  // field
                hex("B3AE60"),  // annotation
                hex("BCBEC4"),  // classReference
                hex("CED0D6"),  // caret
                hex("1F2024"),  // currentLine
                hex("4A2425"),  // breakpointLine
                hex("4A3A1E"),  // executionLine
                hex("4B5059"),  // lineNumber
                hex("323438"),  // indentGuide
                hex("3E4145"),  // matchedBracket
                hex("548AF7")   // hyperlink
        );
    }

    public static EditorPalette islandsLight() {
        return new EditorPalette(
                hex("FFFFFF"),  // background
                hex("080808"),  // foreground
                hex("0033B3"),  // keyword
                hex("067D17"),  // string
                hex("1750EB"),  // number
                hex("8C8C8C"),  // comment
                hex("8C8C8C"),  // docComment
                hex("00627A"),  // methodDeclaration
                hex("00627A"),  // instanceMethod
                hex("871094"),  // field
                hex("9E880D"),  // annotation
                hex("080808"),  // classReference
                hex("000000"),  // caret
                hex("F5F8FE"),  // currentLine
                hex("FFE5E5"),  // breakpointLine
                hex("FFF4CE"),  // executionLine
                hex("AEB3C2"),  // lineNumber
                hex("EBECF0"),  // indentGuide
                hex("D4D4D4"),  // matchedBracket
                hex("1750EB")   // hyperlink
        );
    }

    /**
     * Parses a scheme colour. IntelliJ colour schemes drop leading zeroes ({@code 33b3} means
     * {@code 0033b3}), so the value is padded before decoding.
     */
    static Color hex(String value) {
        String digits = value.startsWith("#") ? value.substring(1) : value;
        if (digits.length() > 6) {
            throw new IllegalArgumentException("Not an RRGGBB colour: " + value);
        }
        return new Color(Integer.parseInt("0".repeat(6 - digits.length()) + digits, 16));
    }
}
