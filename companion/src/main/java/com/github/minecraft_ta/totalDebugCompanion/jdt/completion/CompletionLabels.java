package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.codeassist.InternalCompletionContext;
import org.eclipse.jdt.internal.codeassist.InternalCompletionProposal;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding;

import java.util.Arrays;
import java.util.StringJoiner;

/** Display text for the proposal kinds supported by the editor popup. */
final class CompletionLabels {
    private CompletionLabels() {
    }

    static boolean supports(CompletionProposal proposal, CompletionContext context) {
        return switch (proposal.getKind()) {
            case CompletionProposal.METHOD_REF, CompletionProposal.METHOD_NAME_REFERENCE,
                    CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER, CompletionProposal.CONSTRUCTOR_INVOCATION -> !context.isInJavadoc();
            case CompletionProposal.LAMBDA_EXPRESSION, CompletionProposal.TYPE_REF, CompletionProposal.PACKAGE_REF,
                    CompletionProposal.FIELD_REF, CompletionProposal.FIELD_REF_WITH_CASTED_RECEIVER,
                    CompletionProposal.ANNOTATION_ATTRIBUTE_REF, CompletionProposal.LOCAL_VARIABLE_REF,
                    CompletionProposal.VARIABLE_DECLARATION, CompletionProposal.KEYWORD, CompletionProposal.LABEL_REF -> true;
            default -> false;
        };
    }

    static String name(CompletionProposal proposal) {
        return switch (proposal.getKind()) {
            case CompletionProposal.TYPE_REF -> type(proposal.getSignature());
            case CompletionProposal.PACKAGE_REF -> text(proposal.getDeclarationSignature());
            case CompletionProposal.LAMBDA_EXPRESSION -> "";
            default -> text(proposal.getName()).isEmpty() ? text(proposal.getCompletion()) : text(proposal.getName());
        };
    }

    static void populate(CompletionProposal proposal, CompletionItem item, CompletionContext context) {
        String name = name(proposal), detail = "", resultType = "";
        switch (proposal.getKind()) {
            case CompletionProposal.METHOD_REF, CompletionProposal.METHOD_NAME_REFERENCE,
                    CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER, CompletionProposal.CONSTRUCTOR_INVOCATION,
                    CompletionProposal.LAMBDA_EXPRESSION -> {
                char[][] types = Signature.getParameterTypes(Signature.removeCapture(proposal.getSignature()));
                char[][] names = proposal.findParameterNames(null);
                StringJoiner arguments = new StringJoiner(", ", "(", ")");
                for (int i = 0; i < types.length; i++) {
                    String parameterType = type(types[i]);
                    if (i == types.length - 1 && Flags.isVarargs(proposal.getFlags()) && parameterType.endsWith("[]")) {
                        parameterType = parameterType.substring(0, parameterType.length() - 2) + "...";
                    }
                    arguments.add(parameterType + " " + new String(names[i]));
                }
                detail = arguments.toString();
                if (proposal.getKind() == CompletionProposal.LAMBDA_EXPRESSION) detail += " ->";
                if (!proposal.isConstructor()) resultType = type(Signature.getReturnType(proposal.getSignature()));
            }
            case CompletionProposal.TYPE_REF -> resultType = new String(Signature.getSignatureQualifier(proposal.getSignature()));
            case CompletionProposal.FIELD_REF, CompletionProposal.FIELD_REF_WITH_CASTED_RECEIVER,
                    CompletionProposal.ANNOTATION_ATTRIBUTE_REF -> {
                if (text(proposal.getCompletion()).startsWith("this.")) name = text(proposal.getCompletion());
                resultType = type(proposal.getSignature());
                var field = sourceField(proposal, context);
                String initializer = initializer(field);
                if (!initializer.isEmpty()) {
                    detail = " (= " + (initializer.length() > 40 ? initializer.substring(0, 39) + "…" : initializer) + ")";
                }
            }
            case CompletionProposal.LOCAL_VARIABLE_REF, CompletionProposal.VARIABLE_DECLARATION -> resultType = type(proposal.getSignature());
        }
        item.setPresentation(name, detail, resultType);
    }

    private static String initializer(FieldBinding field) {
        if (field == null || field.sourceField() == null) return "";
        var declaration = field.sourceField();
        if (declaration.initialization != null) return declaration.initialization.toString().replace('\n', ' ').replace('\r', ' ');
        if (!(field.declaringClass instanceof SourceTypeBinding type) || type.scope == null) return "";
        // Completion's diet parser skips initializer ASTs, but retains their source ranges and in-memory source.
        char[] source = type.scope.referenceContext.compilationResult.compilationUnit.getContents();
        int start = declaration.sourceEnd + 1, end = Math.min(source.length, declaration.declarationEnd + 1);
        if (end <= start) return "";
        String text = new String(source, start, end - start).trim();
        if (!text.startsWith("=")) return "";
        text = text.substring(1).trim();
        if (text.endsWith(";") || text.endsWith(",")) text = text.substring(0, text.length() - 1).trim();
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    private static FieldBinding sourceField(CompletionProposal proposal, CompletionContext context) {
        if (!Flags.isStatic(proposal.getFlags()) || !Flags.isFinal(proposal.getFlags())) return null;
        if (proposal instanceof InternalCompletionProposal internal
                && internal.getBinding() instanceof FieldBinding field) return field;
        if (context instanceof InternalCompletionContext internal && context.isExtended()) {
            var fields = internal.getVisibleFields();
            String owner = Signature.toString(text(proposal.getDeclarationSignature())).replace('.', '/');
            for (int i = 0; i < fields.size(); i++) {
                var field = (FieldBinding) fields.elementAt(i);
                if (text(field.name).equals(text(proposal.getName())) && field.declaringClass != null
                        && owner.equals(text(field.declaringClass.constantPoolName()))) return field;
            }
        }
        return null;
    }

    static String type(char[] signature) {
        if (signature == null) return "";
        char[] value = Signature.removeCapture(signature);
        int arrays = Signature.getArrayCount(value);
        if (arrays > 0) return type(Signature.getElementType(value)) + "[]".repeat(arrays);
        if (value[0] == Signature.C_STAR) return "?";
        if (value[0] == Signature.C_EXTENDS || value[0] == Signature.C_SUPER) {
            return (value[0] == Signature.C_EXTENDS ? "? extends " : "? super ")
                    + type(Arrays.copyOfRange(value, 1, value.length));
        }
        String name = new String(Signature.getSignatureSimpleName(Signature.getTypeErasure(value)));
        char[][] arguments = Signature.getTypeArguments(value);
        if (arguments.length == 0) return name;
        StringJoiner generics = new StringJoiner(", ", "<", ">");
        for (char[] argument : arguments) generics.add(type(argument));
        return name + generics;
    }

    static String text(char[] value) {
        return value == null ? "" : new String(value);
    }
}
