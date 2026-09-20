package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameLPAREN;
import static org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameLBRACE;

public class SnippetCompletionProposalProvider {

    public static List<CompletionItem> getSnippets(ICompilationUnit unit, CustomCompletionRequestor requestor) {
        var context = requestor.getContext();
        if (context.getToken() == null || (context.getTokenLocation() & CompletionContext.TL_STATEMENT_START) == 0)
            return Collections.emptyList();

        var token = new String(context.getToken());
        boolean existingHeader = hasFollowingHeader(unit, context.getTokenEnd() + 1);
        return Arrays.stream(Snippets.values()).filter(s -> s.key.startsWith(token))
                .filter(s -> !existingHeader || s != Snippets.IF && s != Snippets.FOR).map(s -> {
            var item = new CompletionItem(requestor);

            item.setPresentation(s.key, "  " + s.description, "");
            item.setKind(CompletionItemKind.SNIPPET);
            item.addTextEdit(new CustomTextEdit(
                    new Range(context.getTokenStart(), context.getTokenEnd() + 1 - context.getTokenStart()),
                    s.text.replace("\n", "\n" + indentationAt(unit, context.getTokenStart()))
            ));

            return item;
        }).toList();
    }

    private static boolean hasFollowingHeader(ICompilationUnit unit, int offset) {
        try {
            String source = unit.getSource();
            if (offset >= source.length()) return false;
            var scanner = ToolFactory.createScanner(false, false, false, JdtConfiguration.JAVA_VERSION);
            scanner.setSource(source.toCharArray());
            scanner.resetTo(offset, source.length() - 1);
            int next = scanner.getNextToken();
            return next == TokenNameLPAREN || next == TokenNameLBRACE;
        } catch (JavaModelException failure) {
            throw new IllegalStateException("Cannot read statement template context", failure);
        } catch (InvalidInputException unfinished) {
            return false;
        }
    }

    static String indentationAt(ICompilationUnit unit, int offset) {
        try {
            String source = unit.getSource();
            int position = Math.max(0, Math.min(offset, source.length()));
            int start = source.lastIndexOf('\n', position - 1) + 1;
            int end = start;
            while (end < source.length() && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) end++;
            return source.substring(start, end);
        } catch (JavaModelException e) {
            throw new IllegalStateException("Cannot read snippet indentation", e);
        }
    }

    private enum Snippets {
        SOUT("sout", "logln(${0});", "log value"),
        LOG("log", "log(${0});", "log without newline"),
        LOGLN("logln", "logln(${0});", "log value"),
        FOR("for", "for (var ${2:item} : ${1:items}) {\n\t${0}\n}", "iterate elements"),
        FORI("fori", "for (int ${1:i} = ${2:0}; ${1:i} < ${3:limit}; ${1:i}++) {\n\t${0}\n}", "iterate indices"),
        IF("if", "if (${1:condition}) {\n\t${0}\n}", "if true"),
        IF_NOTNULL("ifnotnull", "if (${1:var} != null) {\n\t${0}\n}", "if not null");

        private final String key;
        private final String text;
        private final String description;

        Snippets(String key, String text, String description) {
            this.key = key;
            this.text = text;
            this.description = description;
        }
    }
}
