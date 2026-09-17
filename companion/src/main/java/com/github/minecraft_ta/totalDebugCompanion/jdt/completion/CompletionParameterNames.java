package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.naming.MethodParameterNames;
import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.codeassist.InternalCompletionContext;
import org.eclipse.jdt.internal.codeassist.InternalCompletionProposal;
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Seed proposals once, before JDT's lazy Java-model lookup or completion rendering. */
final class CompletionParameterNames {
    static void prepare(CompletionProposal proposal, CompletionContext context) {
        switch (proposal.getKind()) {
            case CompletionProposal.METHOD_REF, CompletionProposal.METHOD_NAME_REFERENCE,
                    CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER, CompletionProposal.CONSTRUCTOR_INVOCATION,
                    CompletionProposal.LAMBDA_EXPRESSION -> { }
            default -> { return; }
        }
        char[][] types = Signature.getParameterTypes(Signature.removeCapture(proposal.getSignature()));
        String[] names = null;
        if (proposal instanceof InternalCompletionProposal internal && internal.getBinding() instanceof MethodBinding binding) {
            MethodBinding original = binding.original();
            var source = original.sourceMethod();
            if (source != null && source.arguments != null && source.arguments.length == types.length) {
                names = Arrays.stream(source.arguments).map(argument -> new String(argument.name)).toArray(String[]::new);
            } else if (original.declaringClass.isBinaryBinding()) {
                String[] raw = Arrays.stream(original.parameterNames).map(String::new).toArray(String[]::new);
                int prefix = 0;
                // ECJ already removes these for nongeneric constructors, but keeps them with a generic signature.
                if (original.isConstructor() && raw.length != types.length) {
                    if (original.declaringClass.isEnum()) prefix += 2;
                    var enclosing = original.declaringClass.syntheticEnclosingInstanceTypes();
                    if (enclosing != null) prefix += enclosing.length;
                }
                if (raw.length >= prefix + types.length) names = Arrays.copyOfRange(raw, prefix, prefix + types.length);
            }
        }
        if (names == null) {
            // Array methods and proposals without a declaration still use the shared fallback.
            StringBuilder descriptor = new StringBuilder("(");
            for (char[] type : types) {
                String erased = new String(Signature.getTypeErasure(type));
                int arrays = Signature.getArrayCount(erased);
                String element = erased.substring(arrays);
                if (element.startsWith("T")) element = "Ljava/lang/Object;";
                else if (element.startsWith("Q")) element = "L" + element.substring(1);
                descriptor.append("[".repeat(arrays)).append(element.replace('.', '/'));
            }
            names = MethodParameterNames.resolve(new MethodParameterNames.Method("", "", descriptor + ")V",
                    proposal.getFlags(), null), null);
        }
        if (proposal.getKind() == CompletionProposal.LAMBDA_EXPRESSION
                && context instanceof InternalCompletionContext internal && context.isExtended()) {
            avoidEnclosingNames(names, internal);
        }
        proposal.setParameterNames(Arrays.stream(names).map(String::toCharArray).toArray(char[][]::new));
    }

    private static void avoidEnclosingNames(String[] names, InternalCompletionContext context) {
        Set<String> used = new HashSet<>();
        var locals = context.getVisibleLocalVariables();
        for (int i = 0; i < locals.size(); i++) {
            used.add(new String(((LocalVariableBinding) locals.elementAt(i)).name));
        }
        // The variable being initialized is omitted from JDT's visible completion candidates.
        if (context.getCompletionNodeParent() instanceof LocalDeclaration local) used.add(new String(local.name));
        Set<String> declared = new HashSet<>(Arrays.asList(names));
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (used.contains(name)) {
                int suffix = 1;
                while (used.contains(name + suffix) || declared.contains(name + suffix)) suffix++;
                names[i] = name + suffix;
            }
            used.add(names[i]);
        }
    }
}
