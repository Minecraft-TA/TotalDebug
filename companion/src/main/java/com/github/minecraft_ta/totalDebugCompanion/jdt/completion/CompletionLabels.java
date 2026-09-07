package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.Signature;

import java.util.StringJoiner;

/** Display text for the proposal kinds supported by the editor popup. */
final class CompletionLabels {
    private CompletionLabels() {
    }

    static String label(CompletionProposal proposal, CompletionContext context) {
        return switch (proposal.getKind()) {
            case CompletionProposal.METHOD_REF, CompletionProposal.METHOD_NAME_REFERENCE,
                    CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER, CompletionProposal.CONSTRUCTOR_INVOCATION ->
                    context.isInJavadoc() ? null : method(proposal, false);
            case CompletionProposal.LAMBDA_EXPRESSION -> method(proposal, true);
            case CompletionProposal.TYPE_REF -> {
                String qualified = Signature.toString(new String(proposal.getSignature()));
                int generics = qualified.indexOf('<');
                int split = qualified.lastIndexOf('.', generics < 0 ? qualified.length() : generics);
                yield split < 0 ? qualified : qualified.substring(split + 1) + " - " + qualified.substring(0, split);
            }
            case CompletionProposal.PACKAGE_REF -> text(proposal.getDeclarationSignature());
            case CompletionProposal.FIELD_REF, CompletionProposal.FIELD_REF_WITH_CASTED_RECEIVER,
                    CompletionProposal.ANNOTATION_ATTRIBUTE_REF -> {
                String completion = text(proposal.getCompletion());
                yield (completion.startsWith("this.") ? completion : text(proposal.getName()))
                        + " : " + type(proposal.getSignature());
            }
            case CompletionProposal.LOCAL_VARIABLE_REF, CompletionProposal.VARIABLE_DECLARATION ->
                    text(proposal.getCompletion()) + " : " + type(proposal.getSignature());
            case CompletionProposal.KEYWORD, CompletionProposal.LABEL_REF -> text(proposal.getCompletion());
            default -> null;
        };
    }

    private static String method(CompletionProposal proposal, boolean lambda) {
        char[][] types = Signature.getParameterTypes(Signature.removeCapture(proposal.getSignature()));
        StringJoiner arguments = new StringJoiner(", ", "(", ")");
        for (int i = 0; i < types.length; i++) {
            String name = type(types[i]);
            if (i == types.length - 1 && Flags.isVarargs(proposal.getFlags()) && name.endsWith("[]")) {
                name = name.substring(0, name.length() - 2) + "...";
            }
            arguments.add(name + " arg" + i);
        }
        String label = (lambda ? "" : text(proposal.getName())) + arguments;
        if (lambda) label += " ->";
        if (!proposal.isConstructor()) label += " : " + type(Signature.getReturnType(proposal.getSignature()));
        return label;
    }

    static String type(char[] signature) {
        if (signature == null) return "";
        char[] value = Signature.removeCapture(signature);
        int arrays = Signature.getArrayCount(value);
        if (arrays > 0) return type(Signature.getElementType(value)) + "[]".repeat(arrays);
        if (value[0] == Signature.C_STAR) return "?";
        if (value[0] == Signature.C_EXTENDS || value[0] == Signature.C_SUPER) {
            return (value[0] == Signature.C_EXTENDS ? "? extends " : "? super ")
                    + type(java.util.Arrays.copyOfRange(value, 1, value.length));
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
