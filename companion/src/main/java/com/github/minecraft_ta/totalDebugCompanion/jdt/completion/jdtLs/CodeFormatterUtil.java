/*******************************************************************************
 * Copyright (c) 2000, 2021 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Microsoft Corporation - add helper methods to read formatting options from the compilation unit
 *******************************************************************************/
package com.github.minecraft_ta.totalDebugCompanion.jdt.completion.jdtLs;

import org.eclipse.core.runtime.Assert;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.util.Map;

public class CodeFormatterUtil {

    public static int getIndentationLevelAtOffset(ICompilationUnit cu, int offset) {
        var count = 0;
        IBuffer buffer;
        try {
            buffer = cu.getBuffer();
        } catch (JavaModelException e) {
            throw new RuntimeException(e);
        }

        while (offset >= 0) {
            var c = buffer.getChar(offset);
            if (c == '\n')
                break;
            else if (c == '\t')
                count += 1;
            else
                count = 0;

            offset--;
        }

        return count;
    }

    /**
     * Creates a string that represents the given number of indentation units.
     * The returned string can contain tabs and/or spaces depending on the core formatter preferences.
     *
     * @param indentationUnits the number of indentation units to generate
     * @param project          the project from which to get the formatter settings,
     *                         <code>null</code> if the workspace default should be used
     * @return the indent string
     */
    public static String createIndentString(int indentationUnits, IJavaProject project) {
        Map<String, String> options = project != null ? project.getOptions(true) : JavaCore.getOptions();
        return ToolFactory.createCodeFormatter(options).createIndentationString(indentationUnits);
    }

    /**
     * Creates a string that represents the given number of indentation units.
     *
     * @param indentationUnits the number of indentation units to generate
     * @param cu               the compilation unit from which to get the formatter settings
     * @return the indent string
     */
    public static String createIndentString(int indentationUnits, ICompilationUnit cu) {
        Map<String, String> options = cu != null ? cu.getOptions(true) : JavaCore.getOptions();
        return ToolFactory.createCodeFormatter(options).createIndentationString(indentationUnits);
    }

    /**
     * Gets the current tab width.
     *
     * @param project The project where the source is used, used for project specific options or
     *                <code>null</code> if the project is unknown and the workspace default should be used
     * @return The tab width
     */
    public static int getTabWidth(IJavaProject project) {
        /*
         * If the tab-char is SPACE, FORMATTER_INDENTATION_SIZE is not used
         * by the core formatter.
         * We piggy back the visual tab length setting in that preference in
         * that case.
         */
        String key;
        if (JavaCore.SPACE.equals(getCoreOption(project, DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR)))
            key = DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE;
        else
            key = DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE;

        return getCoreOption(project, key, 4);
    }

    /**
     * Gets the current tab width.
     *
     * @param cu the compilation unit from which to get the formatter settings
     * @return The tab width
     */
    public static int getTabWidth(ICompilationUnit cu) {
        /*
         * If the tab-char is SPACE, FORMATTER_INDENTATION_SIZE is not used
         * by the core formatter.
         * We piggy back the visual tab length setting in that preference in
         * that case.
         */
        String key;
        if (JavaCore.SPACE.equals(getCoreOption(cu, DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR)))
            key = DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE;
        else
            key = DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE;

        return getCoreOption(cu, key, 4);
    }

    /**
     * Returns the current indent width.
     *
     * @param project the project where the source is used or,
     *                <code>null</code> if the project is unknown and the workspace default should be used
     * @return the indent width
     * @since 3.1
     */
    public static int getIndentWidth(IJavaProject project) {
        String key;
        if (DefaultCodeFormatterConstants.MIXED.equals(getCoreOption(project, DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR)))
            key = DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE;
        else
            key = DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE;

        return getCoreOption(project, key, 4);
    }

    /**
     * Returns the current indent width.
     *
     * @param cu the compilation unit from which to get the formatter settings
     * @return the indent width
     */
    public static int getIndentWidth(ICompilationUnit cu) {
        String key;
        if (DefaultCodeFormatterConstants.MIXED.equals(getCoreOption(cu, DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR)))
            key = DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE;
        else
            key = DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE;

        return getCoreOption(cu, key, 4);
    }

    /**
     * Returns the possibly <code>project</code>-specific core preference defined under <code>key</code>.
     *
     * @param project the project to get the preference from,
     *                or <code>null</code> to get the global preference
     * @param key     the key of the preference
     * @return the value of the preference
     * @since 3.1
     */
    private static String getCoreOption(IJavaProject project, String key) {
        if (project == null)
            return JavaCore.getOption(key);
        return project.getOption(key, true);
    }

    /**
     * Returns the possibly <code>project</code>-specific core preference defined under <code>key</code>,
     * or <code>def</code> if the value is not a integer.
     *
     * @param project the project to get the preference from,
     *                or <code>null</code> to get the global preference
     * @param key     the key of the preference
     * @param def     the default value
     * @return the value of the preference
     * @since 3.1
     */
    private static int getCoreOption(IJavaProject project, String key, int def) {
        try {
            return Integer.parseInt(getCoreOption(project, key));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String getCoreOption(ICompilationUnit cu, String key) {
        if (cu == null)
            return JavaCore.getOption(key);
        return cu.getOptions(true).get(key);
    }

    private static int getCoreOption(ICompilationUnit cu, String key, int def) {
        try {
            return Integer.parseInt(getCoreOption(cu, key));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // transition code

    /**
     * Old API. Consider to use format2 (TextEdit)
     *
     * @param kind             Use to specify the kind of the code snippet to format.
     *                         It can be any of the kind constants defined in {@link CodeFormatter}
     * @param source           The source to format
     * @param indentationLevel The initial indentation level, used to shift left/right the entire source fragment.
     *                         An initial indentation level of zero or below has no effect.
     * @param lineSeparator    The line separator to use in formatted source,
     *                         if set to <code>null</code>, then the platform default one will be used.
     * @param project          The project from which to retrieve the formatter options from
     *                         If set to <code>null</code>, then use the current settings from {@link JavaCore#getOptions()}.
     * @return the formatted source string
     */
    public static String format(int kind, String source, int indentationLevel, String lineSeparator, IJavaProject project) {
        Map<String, String> options = project != null ? project.getOptions(true) : null;
        return format(kind, source, indentationLevel, lineSeparator, options);
    }

    /**
     * Old API. Consider to use format2 (TextEdit)
     *
     * @param kind             Use to specify the kind of the code snippet to format.
     *                         It can be any of the kind constants defined in {@link CodeFormatter}
     * @param source           The source to format
     * @param indentationLevel The initial indentation level, used to shift left/right the entire source fragment.
     *                         An initial indentation level of zero or below has no effect.
     * @param lineSeparator    The line separator to use in formatted source,
     *                         if set to <code>null</code>, then the platform default one will be used.
     * @param options          The options map to use for formatting with the default code formatter.
     *                         Recognized options are documented on {@link JavaCore#getDefaultOptions()}.
     *                         If set to <code>null</code>, then use the current settings from {@link JavaCore#getOptions()}.
     * @return the formatted source string
     */
    public static String format(int kind, String source, int indentationLevel, String lineSeparator, Map<String, String> options) {
        TextEdit edit = format2(kind, source, indentationLevel, lineSeparator, options);
        if (edit == null) {
            return source;
        } else {
            Document document = new Document(source);
            try {
                edit.apply(document, TextEdit.NONE);
            } catch (BadLocationException e) {
                e.printStackTrace();
                Assert.isTrue(false, "Formatter created edits with wrong positions: " + e.getMessage()); //$NON-NLS-1$
            }
            return document.get();
        }
    }

    /**
     * Creates edits that describe how to format the given string.
     * Returns <code>null</code> if the code could not be formatted for the given kind.
     *
     * @param kind             Use to specify the kind of the code snippet to format.
     *                         It can be any of the kind constants defined in {@link CodeFormatter}
     * @param source           The source to format
     * @param offset           The given offset to start recording the edits (inclusive).
     * @param length           the given length to stop recording the edits (exclusive).
     * @param indentationLevel The initial indentation level, used to shift left/right the entire source fragment.
     *                         An initial indentation level of zero or below has no effect.
     * @param lineSeparator    The line separator to use in formatted source,
     *                         if set to <code>null</code>, then the platform default one will be used.
     * @param options          The options map to use for formatting with the default code formatter.
     *                         Recognized options are documented on {@link JavaCore#getDefaultOptions()}.
     *                         If set to <code>null</code>, then use the current settings from {@link JavaCore#getOptions()}.
     * @return an TextEdit describing the changes required to format source
     * @throws IllegalArgumentException If the offset and length are not inside the string, a IllegalArgumentException is thrown.
     */
    public static TextEdit format2(int kind, String source, int offset, int length, int indentationLevel, String lineSeparator, Map<String, String> options) {
        if (offset < 0 || length < 0 || offset + length > source.length()) {
            throw new IllegalArgumentException("offset or length outside of string. offset: " + offset + ", length: " + length + ", string size: " + source.length()); //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
        }
        return ToolFactory.createCodeFormatter(options).format(kind, source, offset, length, indentationLevel, lineSeparator);
    }

    /**
     * Creates edits that describe how to format the given string.
     * Returns <code>null</code> if the code could not be formatted for the given kind.
     *
     * @param kind             Use to specify the kind of the code snippet to format.
     *                         It can be any of the kind constants defined in {@link CodeFormatter}
     * @param source           The source to format
     * @param indentationLevel The initial indentation level, used to shift left/right the entire source fragment.
     *                         An initial indentation level of zero or below has no effect.
     * @param lineSeparator    The line separator to use in formatted source,
     *                         if set to <code>null</code>, then the platform default one will be used.
     * @param options          The options map to use for formatting with the default code formatter.
     *                         Recognized options are documented on {@link JavaCore#getDefaultOptions()}.
     *                         If set to <code>null</code>, then use the current settings from {@link JavaCore#getOptions()}.
     * @return an TextEdit describing the changes required to format source
     * @throws IllegalArgumentException If the offset and length are not inside the string, a IllegalArgumentException is thrown.
     */
    public static TextEdit format2(int kind, String source, int indentationLevel, String lineSeparator, Map<String, String> options) {
        return format2(kind, source, 0, source.length(), indentationLevel, lineSeparator, options);
    }

    private CodeFormatterUtil() {
    }
}
