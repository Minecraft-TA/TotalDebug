package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.jdtLs.CodeFormatterUtil;
import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.ICompilationUnit;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SnippetCompletionProposalProvider {

    public static List<CompletionItem> getSnippets(ICompilationUnit unit, CustomCompletionRequestor requestor) {
        var context = requestor.getContext();
        if (context.getToken() == null || (context.getTokenLocation() & CompletionContext.TL_STATEMENT_START) == 0)
            return Collections.emptyList();

        var token = new String(context.getToken());
        return Arrays.stream(Snippets.values()).filter(s -> s.getKey().startsWith(token)).map(s -> {
            var item = new CompletionItem(requestor);

            item.setLabel(s.getKey());
            item.setKind(CompletionItemKind.KEYWORD);
            item.addTextEdit(new CustomTextEdit(
                    new Range(context.getTokenStart(), token.length()),
                    s.getText().replace("\n", "\n" + "\t".repeat(CodeFormatterUtil.getIndentationLevelAtOffset(unit, context.getTokenStart())))
            ));

            return item;
        }).toList();
    }

    private enum Snippets {
        SOUT("sout", "logln(${0});"),
        LOG("log", "log(${0});"),
        LOGLN("logln", "logln(${0});"),
        FORI("fori", "for (int ${1:i} = ${2:0}; ${1:i} < ${3}; ${1:i}++) {\n\t${4}\n}"),
        IF("iftrue", "if (${1:condition}) {\n\t${0}\n}"),
        IF_NOTNULL("ifnotnull", "if (${1:var} != null) {\n\t${0}\n}");

        private final String key;
        private final String text;

        Snippets(String key, String text) {
            this.key = key;
            this.text = text;
        }

        public String getKey() {
            return key;
        }

        public String getText() {
            return text;
        }
    }
}
