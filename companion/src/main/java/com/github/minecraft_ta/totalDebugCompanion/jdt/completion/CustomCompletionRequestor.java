package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.internal.codeassist.InternalCompletionContext;
import org.eclipse.jdt.internal.codeassist.InternalCompletionProposal;
import org.eclipse.jdt.internal.codeassist.complete.CompletionOnMemberAccess;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.CaptureBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public class CustomCompletionRequestor extends CompletionRequestor implements IProgressMonitor {


    private final List<CompletionProposal> proposals = new ArrayList<>();
    private final BiConsumer<CustomCompletionRequestor, List<CompletionItem>> completionCallback;
    private final int offset;
    private final ICompilationUnit unit;

    private CompletionContext context;
    private CompletionEdits proposalProvider;

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
        List<CompletionItem> candidates = new ArrayList<>();
        for (var proposal : proposals) {
            if (!CompletionLabels.supports(proposal, context)) continue;
            candidates.add(item(proposal));
        }
        candidates.addAll(SubtypeCompletion.find(unit, this, candidates));
        addLiveTemplates(candidates);
        candidates.addAll(SnippetCompletionProposalProvider.getSnippets(this.unit, this));
        List<CompletionItem> result = new ArrayList<>();
        for (var item : CompletionRanking.order(candidates, CompletionLabels.text(context.getToken()))) {
            if (isCanceled()) break;
            if (item.proposal != null) {
                CompletionParameterNames.prepare(item.proposal, context);
                CompletionLabels.populate(item.proposal, item, context);
                this.proposalProvider.populate(item.proposal, item);
                if (item.getTextEdits().isEmpty()) continue;
            }
            result.add(item);
            if (result.size() == 50) break;
        }
        // Downstream expression completion must preserve this order too.
        for (int i = 0; i < result.size(); i++) result.get(i).setRelevance(result.size() - i);
        return result;
    }

    CompletionItem item(CompletionProposal proposal) {
        var item = new CompletionItem(this, proposal);
        item.setKind(mapKind(proposal));
        return item;
    }

    private void addLiveTemplates(List<CompletionItem> items) {
        var node = ((InternalCompletionContext) context).getCompletionNode();
        if (!(node instanceof CompletionOnMemberAccess memberAccess))
            return;

        var memberName = new String(memberAccess.token);
        if (memberName.isBlank())
            return;

        if ("var".startsWith(memberName)) {
            var variableType = memberAccess.receiver.resolvedType;
            if (variableType == null || !variableType.isValidBinding() || variableType.id == TypeIds.T_void)
                return;

            var item = new CompletionItem(this);
            item.setPresentation("var", "", "");
            item.setRelevance(0);
            item.setKind(CompletionItemKind.KEYWORD);
            var start = memberAccess.receiver.sourceStart;
            try {
                var expressionText = this.unit.getBuffer().getText(start, memberAccess.receiver.sourceEnd - start + 1);
                var preStatementTerminationChar = getStatementTerminationChar(this.unit.getBuffer(), memberAccess.receiver.sourceStart - 1, false);
                var postStatementTerminationChar = getStatementTerminationChar(this.unit.getBuffer(), this.offset, true);
                var terminator = postStatementTerminationChar != ';' ? ";" : "";

                var declarationType = variableType instanceof CaptureBinding capture ? capture.upperBound() : variableType;
                String type = proposalProvider.importType(new String(CompletionTypes.uncapture(declarationType).genericTypeSignature()).replace('/', '.'), item);
                var declarationText = type + " ${1:name} = " + expressionText + terminator;
                Range declarationReplacementRange;
                if ((postStatementTerminationChar != '\n' && postStatementTerminationChar != ';') || (preStatementTerminationChar != '\n' && preStatementTerminationChar != ';')) {
                    declarationReplacementRange = new Range(getLineStartOffsetWithoutWhitespace(this.unit.getBuffer(), this.offset), 0);
                    declarationText += "\n" + SnippetCompletionProposalProvider.indentationAt(this.unit, this.offset);

                    item.addTextEdit(new CustomTextEdit(
                            new Range(start, node.sourceEnd - start + 1),
                            "${1:name}"
                    ));
                } else {
                    declarationReplacementRange = new Range(start, node.sourceEnd - start + 1);
                }

                item.addTextEdit(new CustomTextEdit(declarationReplacementRange, declarationText));

                items.add(item);
            } catch (JavaModelException failure) {
                throw new IllegalStateException("Cannot read the completion source", failure);
            }
        }
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
        this.proposalProvider = new CompletionEdits(this.unit, context);
    }

    @Override
    public boolean isTestCodeExcluded() {
        return true;
    }

    protected boolean isFiltered(CompletionProposal proposal) {
        if (isIgnored(proposal.getKind())) return true;
        if (proposal instanceof InternalCompletionProposal internal) {
            var binding = internal.getBinding();
            if (proposal.getKind() == CompletionProposal.TYPE_REF && !Flags.isPublic(proposal.getFlags())
                    && (!(binding instanceof ReferenceBinding type) || type.isBinaryBinding())) return true;
            String program = JavaSnippetSource.PROGRAM_TYPE.replace('.', '/');
            if (binding instanceof MethodBinding method) {
                String owner = new String(method.declaringClass.constantPoolName());
                if (owner.equals(program)) return !Flags.isPublic(proposal.getFlags()) || "run".equals(new String(method.selector));
                var parent = method.declaringClass.superclass();
                if (parent != null && program.equals(new String(parent.constantPoolName()))
                        && "run".equals(new String(method.selector)) && method.parameters.length == 0) return true;
            } else if (binding instanceof FieldBinding field
                    && field.declaringClass != null
                    && program.equals(new String(field.declaringClass.constantPoolName()))) {
                return true;
            } else if (binding instanceof ReferenceBinding type) {
                if (program.equals(new String(type.constantPoolName()))) return true;
                var parent = type.superclass();
                if (!type.isBinaryBinding() && parent != null && program.equals(new String(parent.constantPoolName()))) return true;
                // The linker relaxes member access, not access to the declaring classes.
                if (type.isBinaryBinding() && type.fPackage.compoundName.length != 0) {
                    for (var enclosing = type; enclosing != null; enclosing = enclosing.enclosingType()) {
                        if (!enclosing.isPublic()) return true;
                    }
                }
            }
        }
        return proposal.getKind() == CompletionProposal.TYPE_REF && Flags.isPrivate(proposal.getFlags());
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

        var items = convertProposals();
        if (!isCanceled()) this.completionCallback.accept(this, items);
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
