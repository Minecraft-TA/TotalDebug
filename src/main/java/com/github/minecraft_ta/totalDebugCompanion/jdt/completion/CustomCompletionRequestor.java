package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.jdtLs.CodeFormatterUtil;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.jdtLs.CompletionProposalDescriptionProvider;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.jdtLs.CompletionProposalReplacementProvider;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.codeassist.InternalCompletionContext;
import org.eclipse.jdt.internal.codeassist.complete.CompletionOnMemberAccess;
import org.eclipse.jdt.internal.compiler.lookup.ParameterizedTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class CustomCompletionRequestor extends CompletionRequestor implements IProgressMonitor {


    private final List<CompletionProposal> proposals = new ArrayList<>();
    private final BiConsumer<CustomCompletionRequestor, List<CompletionItem>> completionCallback;
    private final int offset;
    private final ICompilationUnit unit;

    private CompletionContext context;
    private CompletionProposalDescriptionProvider descriptionProvider;
    private CompletionProposalReplacementProvider proposalProvider;

    private volatile boolean cancelled;
    private long startTime;

    public CustomCompletionRequestor(
            ICompilationUnit unit,
            int offset,
            BiConsumer<CustomCompletionRequestor, List<CompletionItem>> completionCallback
    ) {
        this.unit = unit;
        this.offset = offset;
        this.completionCallback = completionCallback;
        this.setRequireExtendedContext(true);
    }

    @Override
    public void accept(CompletionProposal proposal) {
        if (this.cancelled)
            return;

        if (isFiltered(proposal))
            return;
        if (!isIgnored(proposal.getKind())) {
            if (proposal.getKind() != CompletionProposal.POTENTIAL_METHOD_DECLARATION) {
                proposals.add(proposal);
            }
        }
    }

    private List<CompletionItem> convertProposals() {
        var items = proposals.stream()
                .sorted(new CompletionProposalComparator())
                .limit(50)
                .map(this::toCompletionItem)
                .filter(Objects::nonNull)
                .sorted(new CompletionItemComparator())
                .collect(Collectors.toList());

        prependLiveTemplates(items);
        items.addAll(SnippetCompletionProposalProvider.getSnippets(this.unit, this));
        return items;
    }

    private CompletionItem toCompletionItem(CompletionProposal proposal) {
        final CompletionItem item = new CompletionItem(this);
        item.setRelevance(mapRelevance(proposal));
        item.setKind(mapKind(proposal));
        /*if (Flags.isDeprecated(proposal.getFlags())) {
            if (preferenceManager.getClientPreferences().isCompletionItemTagSupported()) {
                $.setTags(List.of(CompletionItemTag.Deprecated));
            } else {
                $.setDeprecated(true);
            }
        }*/

        if (!this.descriptionProvider.updateDescription(proposal, item))
            return null;

        this.proposalProvider.updateReplacement(proposal, item, '\0');

        if (item.getTextEdits().stream().allMatch(edit -> edit.getNewText().isEmpty()))
            return null;

        //Fix first completion
        var mainEditRange = item.getTextEdits().get(0).getRange();
        if (mainEditRange.getEndOffset() > this.offset && !item.getTextEdits().get(0).getNewText().endsWith(";"))
            mainEditRange.setLength(mainEditRange.getLength() - (mainEditRange.getEndOffset() - this.offset));
        return item;
    }

    private void prependLiveTemplates(List<CompletionItem> items) {
        var node = ((InternalCompletionContext) context).getCompletionNode();
        if (!(node instanceof CompletionOnMemberAccess memberAccess))
            return;

        var memberName = new String(memberAccess.token);
        if (memberName.isBlank())
            return;

        if ("var".startsWith(memberName)) {
            var variableType = memberAccess.receiver.resolvedType;
            if (variableType.id == TypeIds.T_void)
                return;

            var item = new CompletionItem(this);
            item.setLabel("var");
            item.setRelevance(0);
            item.setKind(CompletionItemKind.KEYWORD);
            var start = memberAccess.receiver.sourceStart;
            try {
                var expressionText = this.unit.getBuffer().getText(start, memberAccess.receiver.sourceEnd - start + 1);
                var preStatementTerminationChar = getStatementTerminationChar(this.unit.getBuffer(), memberAccess.receiver.sourceStart - 1, false);
                var postStatementTerminationChar = getStatementTerminationChar(this.unit.getBuffer(), this.offset, true);
                var terminator = postStatementTerminationChar != ';' ? ";" : "";

                var declarationText = new String(variableType.shortReadableName()) + " ${1:name} = " + expressionText + terminator;
                Range declarationReplacementRange;
                if ((postStatementTerminationChar != '\n' && postStatementTerminationChar != ';') || (preStatementTerminationChar != '\n' && preStatementTerminationChar != ';')) {
                    declarationReplacementRange = new Range(getLineStartOffsetWithoutWhitespace(this.unit.getBuffer(), this.offset), 0);
                    declarationText += "\n" + "\t".repeat(CodeFormatterUtil.getIndentationLevelAtOffset(this.unit, this.offset));

                    item.addTextEdit(new CustomTextEdit(
                            new Range(start, node.sourceEnd - start + 1),
                            "${1:name}"
                    ));
                } else {
                    declarationReplacementRange = new Range(start, node.sourceEnd - start + 1);
                }

                item.addTextEdit(new CustomTextEdit(declarationReplacementRange, declarationText));
                addAllImportsForType(variableType, item);

                items.add(0, item);
            } catch (Throwable ignored) {}
        }
    }

    private void addAllImportsForType(TypeBinding variableType, CompletionItem item) {
        List<String> names = new ArrayList<>(2);
        names.add(new String(variableType.readableName()));
        if (variableType instanceof ParameterizedTypeBinding parameterizedTypeBinding) {
            for (TypeBinding argument : parameterizedTypeBinding.typeArguments())
                names.add(new String(argument.readableName()));
        }

        this.proposalProvider.singleImportRewrite(names.toArray(new String[0])).forEach(item::addTextEdit);
    }

    public int mapRelevance(CompletionProposal proposal) {
        int baseRelevance = proposal.getRelevance();
        return switch (proposal.getKind()) {
            case CompletionProposal.LABEL_REF -> baseRelevance + 1;
            case CompletionProposal.KEYWORD -> baseRelevance + 2;
            case CompletionProposal.TYPE_REF, CompletionProposal.ANONYMOUS_CLASS_DECLARATION, CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION ->
                    baseRelevance + 3;
            case CompletionProposal.METHOD_REF, CompletionProposal.CONSTRUCTOR_INVOCATION, CompletionProposal.METHOD_NAME_REFERENCE, CompletionProposal.METHOD_DECLARATION, CompletionProposal.ANNOTATION_ATTRIBUTE_REF, CompletionProposal.POTENTIAL_METHOD_DECLARATION ->
                    baseRelevance + 4;
            case CompletionProposal.FIELD_REF -> baseRelevance + 5;
            case CompletionProposal.LOCAL_VARIABLE_REF, CompletionProposal.VARIABLE_DECLARATION -> baseRelevance + 6;
            default -> baseRelevance;
        };
    }

    private CompletionItemKind mapKind(CompletionProposal proposal) {
        int kind = proposal.getKind();
        int flags = proposal.getFlags();
        switch (kind) {
            case CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION:
            case CompletionProposal.CONSTRUCTOR_INVOCATION:
                return CompletionItemKind.CONSTRUCTOR;
            case CompletionProposal.ANONYMOUS_CLASS_DECLARATION:
            case CompletionProposal.TYPE_REF:
                if (Flags.isInterface(flags)) {
                    return CompletionItemKind.INTERFACE;
                } else if (Flags.isEnum(flags)) {
                    return CompletionItemKind.ENUM;
                }
                return CompletionItemKind.CLASS;
            case CompletionProposal.FIELD_IMPORT:
            case CompletionProposal.METHOD_IMPORT:
            case CompletionProposal.PACKAGE_REF:
            case CompletionProposal.TYPE_IMPORT:
                return CompletionItemKind.IMPORT;
            case CompletionProposal.FIELD_REF:
                if (Flags.isEnum(flags))
                    return CompletionItemKind.ENUM_MEMBER;
                if (Flags.isStatic(flags) && Flags.isFinal(flags))
                    return CompletionItemKind.CONSTANT;
                return CompletionItemKind.FIELD;
            case CompletionProposal.FIELD_REF_WITH_CASTED_RECEIVER:
                return CompletionItemKind.FIELD;
            case CompletionProposal.KEYWORD:
                return CompletionItemKind.KEYWORD;
            case CompletionProposal.LABEL_REF:
                return CompletionItemKind.LABEL;
            case CompletionProposal.LOCAL_VARIABLE_REF:
            case CompletionProposal.VARIABLE_DECLARATION:
                return CompletionItemKind.VARIABLE;
            case CompletionProposal.METHOD_DECLARATION:
            case CompletionProposal.METHOD_REF:
            case CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER:
            case CompletionProposal.METHOD_NAME_REFERENCE:
            case CompletionProposal.POTENTIAL_METHOD_DECLARATION:
            case CompletionProposal.LAMBDA_EXPRESSION:
                return CompletionItemKind.METHOD;
            case CompletionProposal.ANNOTATION_ATTRIBUTE_REF:
            case CompletionProposal.JAVADOC_BLOCK_TAG:
            case CompletionProposal.JAVADOC_FIELD_REF:
            case CompletionProposal.JAVADOC_INLINE_TAG:
            case CompletionProposal.JAVADOC_METHOD_REF:
            case CompletionProposal.JAVADOC_PARAM_REF:
            case CompletionProposal.JAVADOC_TYPE_REF:
            case CompletionProposal.JAVADOC_VALUE_REF:
            default:
                return CompletionItemKind.TEXT;
        }
    }

    @Override
    public void acceptContext(CompletionContext context) {
        super.acceptContext(context);
        this.context = context;
        this.descriptionProvider = new CompletionProposalDescriptionProvider(context);
        this.proposalProvider = new CompletionProposalReplacementProvider(this.unit, context, this.offset);
    }

    @Override
    public boolean isTestCodeExcluded() {
        return true;
    }

    protected boolean isFiltered(CompletionProposal proposal) {
        if (isIgnored(proposal.getKind())) {
            return true;
        }
        // Only filter types and constructors from completion.
        switch (proposal.getKind()) {
            case CompletionProposal.CONSTRUCTOR_INVOCATION:
            case CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION:
            case CompletionProposal.JAVADOC_TYPE_REF:
            case CompletionProposal.PACKAGE_REF:
            case CompletionProposal.TYPE_REF:
                return isTypeFiltered(proposal);
            case CompletionProposal.METHOD_REF:
                // Methods from already imported types and packages can still be proposed.
                // Whether the expected type is resolved or not can be told from the required proposal.
                // When the type is missing, an additional proposal could be found.
                if (proposal.getRequiredProposals() != null) {
                    return isTypeFiltered(proposal);
                }
        }
        return false;
    }

    private static final char[][] TYPE_FILTERS = Arrays.stream(new String[]{
            "com.sun",
            "sun.",
            "scala",
            "org.omg",
            "org.jcp",
            "org.omg",
            "org.jline",
            "oshi",
            "javassist.",
            "com.ibm",
            "com.jcraft",
            "akka"
    }).map(String::toCharArray).toArray(char[][]::new);

    protected boolean isTypeFiltered(CompletionProposal proposal) {
        char[] declaringType = getDeclaringType(proposal);
        if (declaringType == null)
            return false;

        for (char[] filter : TYPE_FILTERS) {
            if (CharOperation.fragmentEquals(filter, declaringType, 0, false))
                return true;
        }

        return false;
    }

    /**
     * copied from
     * org.eclipse.jdt.ui.text.java.CompletionProposalCollector.getDeclaringType(CompletionProposal)
     */
    protected final char[] getDeclaringType(CompletionProposal proposal) {
        var ar = switch (proposal.getKind()) {
            case CompletionProposal.METHOD_DECLARATION, CompletionProposal.METHOD_NAME_REFERENCE,
                    CompletionProposal.JAVADOC_METHOD_REF, CompletionProposal.METHOD_REF,
                    CompletionProposal.CONSTRUCTOR_INVOCATION, CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION,
                    CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER, CompletionProposal.ANNOTATION_ATTRIBUTE_REF,
                    CompletionProposal.POTENTIAL_METHOD_DECLARATION, CompletionProposal.ANONYMOUS_CLASS_DECLARATION,
                    CompletionProposal.FIELD_REF, CompletionProposal.FIELD_REF_WITH_CASTED_RECEIVER,
                    CompletionProposal.JAVADOC_FIELD_REF, CompletionProposal.JAVADOC_VALUE_REF -> {
                char[] declaration = proposal.getDeclarationSignature();
                // special methods may not have a declaring type: methods defined on arrays etc.
                // Currently known: class literals don't have a declaring type - use Object
                if (declaration == null)
                    yield "java.lang.Object".toCharArray();
                yield declaration;
            }
            case CompletionProposal.PACKAGE_REF -> proposal.getDeclarationSignature();
            case CompletionProposal.JAVADOC_TYPE_REF, CompletionProposal.TYPE_REF -> proposal.getSignature();
            case CompletionProposal.LOCAL_VARIABLE_REF, CompletionProposal.VARIABLE_DECLARATION, CompletionProposal.KEYWORD,
                    CompletionProposal.LABEL_REF, CompletionProposal.JAVADOC_BLOCK_TAG, CompletionProposal.JAVADOC_INLINE_TAG,
                    CompletionProposal.JAVADOC_PARAM_REF -> null;
            default -> {
                Assert.isTrue(false);
                yield null;
            }
        };

        if (ar == null)
            return null;
        return ar[ar.length - 1] == ';' ? CharOperation.subarray(ar, 1, ar.length - 1) : ar;
    }

    public CompletionContext getContext() {
        return this.context;
    }

    @Override
    public void beginTask(String name, int totalWork) {
        this.startTime = System.nanoTime();
    }

    @Override
    public void done() {
        if (this.cancelled)
            return;

        this.completionCallback.accept(this, convertProposals());
    }

    @Override
    public void internalWorked(double work) {

    }

    @Override
    public boolean isCanceled() {
        return this.cancelled || (System.nanoTime() - startTime) > 1000_000_000_000L;
    }

    @Override
    public void setCanceled(boolean value) {
        this.cancelled = value;
    }

    @Override
    public void setTaskName(String name) {

    }

    @Override
    public void subTask(String name) {

    }

    @Override
    public void worked(int work) {

    }

    @Override
    public boolean isAllowingRequiredProposals(int proposalKind, int requiredProposalKind) {
        return true;
    }

    private static int getLineStartOffsetWithoutWhitespace(IBuffer buffer, int offset) {
        var whitespace = 0;
        while (offset >= 0) {
            var c = buffer.getChar(offset);
            if (c == '\n')
                break;
            else if (Character.isWhitespace(c))
                whitespace++;
            else
                whitespace = 0;

            offset--;
        }

        return offset + whitespace + 1;
    }

    private static char getStatementTerminationChar(IBuffer buffer, int start, boolean suffix) {
        while (suffix ? (start < buffer.getLength()) : (start >= 0)) {
            char c = buffer.getChar(start);
            if (c == '\n' || c == ';')
                return c;
            else if (!Character.isWhitespace(c))
                return c;

            start += (suffix ? 1 : -1);
        }

        throw new IllegalStateException();
    }
}
