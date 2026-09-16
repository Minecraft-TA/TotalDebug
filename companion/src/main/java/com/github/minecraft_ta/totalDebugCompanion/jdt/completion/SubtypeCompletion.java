package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.tth05.jindex.IndexedClass;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.internal.codeassist.InternalCompletionContext;
import org.eclipse.jdt.internal.codeassist.complete.CompletionOnMemberAccess;
import org.eclipse.jdt.internal.codeassist.complete.CompletionOnQualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.CastExpression;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.NameReference;
import org.eclipse.jdt.internal.compiler.ast.Wildcard;
import org.eclipse.jdt.internal.compiler.lookup.*;

import java.util.*;

/** Bounded, index-backed suggestions. Candidate casts are possibilities, never runtime observations. */
final class SubtypeCompletion {
    private static final int MAX_HIERARCHY = 256;
    private static final int MAX_CASTS = 6;

    private record Receiver(Range range, TypeBinding type) { }

    static List<CompletionItem> find(ICompilationUnit unit, CustomCompletionRequestor parent, List<CompletionItem> direct) {
        String token = CompletionLabels.text(parent.getContext().getToken());
        if (token.length() < 2 || parent.isCanceled() || !CompanionClassIndex.isOpen()) return List.of();
        Receiver receiver = receiver((InternalCompletionContext) parent.getContext());
        if (receiver == null || receiver.type() == null || !receiver.type().isValidBinding()
                || receiver.type().isArrayType() || receiver.type().isBaseType() || receiver.type().isTypeVariable()) return List.of();
        String owner = new String(receiver.type().erasure().constantPoolName());
        if (owner.equals("java/lang/Object")) return List.of();
        var indexed = CompanionClassIndex.get().findClass(owner);
        if (indexed == null || Flags.isFinal(indexed.getAccessFlags())) return List.of();
        var implementations = indexed.findImplementations(false);

        Set<String> directMembers = new HashSet<>();
        for (var item : direct) if (item.proposal != null) directMembers.add(memberKey(item.proposal));
        var result = new ArrayList<CompletionItem>();
        String packageName = owner.substring(0, owner.lastIndexOf('/') + 1);
        var accessibleTypes = Arrays.stream(implementations)
                .sorted(Comparator.comparing((IndexedClass type) -> !type.getNameWithPackage().startsWith(packageName))
                        .thenComparing(IndexedClass::getNameWithPackageDot))
                .limit(MAX_HIERARCHY).takeWhile(ignored -> !parent.isCanceled()).filter(SubtypeCompletion::accessible)
                .toList();
        Set<String> castNames = new HashSet<>();
        accessibleTypes.forEach(type -> castNames.add(type.getNameWithPackage()));
        var candidates = accessibleTypes.stream()
                .filter(type -> hasMatch(type, token.toLowerCase(Locale.ROOT), castNames, new HashSet<>())).limit(MAX_CASTS).toList();
        try {
            String source = unit.getSource();
            int offset = parent.getContext().getOffset();
            for (var candidate : candidates) {
                if (parent.isCanceled()) break;
                String typeName = candidate.getNameWithPackageDot().replace('$', '.');
                var lookup = complete(unit, source, receiver.range(), typeName, offset, parent);
                TypeBinding target = target(lookup);
                if (target == null) continue;
                String signature = new String(target.genericTypeSignature()).replace('/', '.');
                String parameterizedName = Signature.toString(signature).replace('$', '.');
                if (!parameterizedName.equals(typeName)) {
                    lookup = complete(unit, source, receiver.range(), parameterizedName, offset, parent);
                }
                int shift = parameterizedName.length() + 5;
                for (var proposal : lookup.proposals) {
                    if (parent.isCanceled()) break;
                    if (proposal.getKind() != CompletionProposal.FIELD_REF && proposal.getKind() != CompletionProposal.METHOD_REF) continue;
                    if (Flags.isStatic(proposal.getFlags()) || parent.isFiltered(proposal)
                            || directMembers.contains(memberKey(proposal))) continue;
                    proposal.setReplaceRange(proposal.getReplaceStart() - shift, proposal.getReplaceEnd() - shift);
                    var item = parent.item(proposal);
                    item.setReceiverCast(new CompletionItem.ReceiverCast(receiver.range(), signature));
                    result.add(item);
                }
            }
        } catch (JavaModelException failure) {
            throw new IllegalStateException("Cannot resolve subtype completions", failure);
        }
        return result;
    }

    private static Receiver receiver(InternalCompletionContext context) {
        var node = context.getCompletionNode();
        Expression expression = receiverExpression(node);
        if (expression != null) {
            if (expression instanceof CastExpression
                    || expression instanceof NameReference name && name.isTypeAccess()) return null;
            return new Receiver(new Range(expression.sourceStart, expression.sourceEnd - expression.sourceStart + 1),
                    expression.resolvedType);
        }
        if (node instanceof CompletionOnQualifiedNameReference name && name.binding instanceof VariableBinding variable) {
            int end = (int) name.sourcePositions[name.tokens.length - 1] + 1;
            return new Receiver(new Range(name.sourceStart, end - name.sourceStart), variable.type);
        }
        return null;
    }

    private static Expression receiverExpression(ASTNode node) {
        return switch (node) {
            case CompletionOnMemberAccess member -> member.receiver;
            case MessageSend message -> message.receiver;
            default -> null;
        };
    }

    private static boolean accessible(IndexedClass type) {
        for (var enclosing = type; enclosing != null; enclosing = enclosing.getEnclosingClass()) {
            if (!Flags.isPublic(enclosing.getAccessFlags()) || Flags.isSynthetic(enclosing.getAccessFlags())) return false;
        }
        // Non-static member classes require an enclosing parameterization as well as their own.
        return type.getEnclosingClass() == null || Flags.isStatic(type.getAccessFlags());
    }

    private static boolean hasMatch(IndexedClass type, String query, Set<String> castNames, Set<String> visited) {
        // Prefer casting to the member's accessible parent instead of every descendant of that parent.
        if (type != null && !visited.isEmpty() && castNames.contains(type.getNameWithPackage())) return false;
        if (type == null || visited.size() >= 32 || !visited.add(type.getNameWithPackage())) return false;
        return Arrays.stream(type.getFields()).anyMatch(field -> !Flags.isStatic(field.getAccessFlags())
                        && field.getName().toLowerCase(Locale.ROOT).contains(query))
                || Arrays.stream(type.getMethods()).anyMatch(method -> !Flags.isStatic(method.getAccessFlags())
                        && !method.getName().startsWith("<") && method.getName().toLowerCase(Locale.ROOT).contains(query))
                || hasMatch(type.getSuperClass(), query, castNames, visited)
                || Arrays.stream(type.getInterfaces()).anyMatch(parent -> hasMatch(parent, query, castNames, visited));
    }

    private static String memberKey(CompletionProposal proposal) {
        String signature = CompletionLabels.text(proposal.getSignature());
        boolean method = proposal.getKind() == CompletionProposal.METHOD_REF
                || proposal.getKind() == CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER;
        // Overrides and covariant returns do not need another entry requiring a cast.
        return (method ? "method:" : "field:") + CompletionLabels.text(proposal.getName())
                + (method ? signature.substring(0, signature.indexOf(')') + 1) : "");
    }

    private static Lookup complete(ICompilationUnit unit, String source, Range range, String type,
                                   int offset, CustomCompletionRequestor parent) throws JavaModelException {
        String cast = "((" + type + ") " + source.substring(range.getOffset(), range.getEndOffset()) + ")";
        String modified = source.substring(0, range.getOffset()) + cast + source.substring(range.getEndOffset());
        modified = withoutArguments(modified, offset + type.length() + 5);
        var copy = new CompilationUnitImpl(unit.getElementName(), modified);
        var lookup = new Lookup();
        copy.codeComplete(offset + type.length() + 5, lookup, new NullProgressMonitor() {
            @Override public boolean isCanceled() { return parent.isCanceled(); }
        });
        return lookup;
    }

    private static String withoutArguments(String source, int offset) {
        int end = offset;
        while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) end++;
        var scanner = ToolFactory.createScanner(false, false, false, JdtConfiguration.JAVA_VERSION);
        scanner.setSource(source.toCharArray());
        scanner.resetTo(end, source.length() - 1);
        try {
            if (scanner.getNextToken() != ITerminalSymbols.TokenNameLPAREN) return source;
            int start = scanner.getCurrentTokenStartPosition(), depth = 1, token;
            while ((token = scanner.getNextToken()) != ITerminalSymbols.TokenNameEOF) {
                if (token == ITerminalSymbols.TokenNameLPAREN) depth++;
                if (token == ITerminalSymbols.TokenNameRPAREN && --depth == 0) {
                    int after = scanner.getCurrentTokenEndPosition() + 1;
                    // JDT omits some inherited methods on cast receivers inside existing calls.
                    // Ask for member access in the temporary copy; the accepted edit retains the real arguments.
                    return source.substring(0, start) + " ".repeat(after - start) + source.substring(after);
                }
            }
        } catch (InvalidInputException ignored) { }
        return source;
    }

    /** Let JDT resolve the cast and its original receiver in the same binding environment. */
    private static TypeBinding target(Lookup lookup) {
        if (!(lookup.context instanceof InternalCompletionContext context)
                || !(receiverExpression(context.getCompletionNode()) instanceof CastExpression cast)
                || !(cast.resolvedType instanceof ReferenceBinding raw) || !raw.isValidBinding()
                || cast.expression.resolvedType == null) return null;
        TypeBinding source = uncapture(cast.expression.resolvedType);
        ReferenceBinding target = raw;
        if (raw instanceof ParameterizedTypeBinding parameterized && raw.isRawType()) {
            ReferenceBinding generic = parameterized.genericType();
            var variables = generic.typeVariables();
            var arguments = new TypeBinding[variables.length];
            Map<TypeBinding, TypeBinding> inferred = new HashMap<>();
            infer(generic.findSuperTypeOriginatingFrom(source.erasure()), source, inferred);
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = inferred.getOrDefault(variables[i],
                        parameterized.environment.createWildcard(generic, i, null, null, Wildcard.UNBOUND));
            }
            var resolved = parameterized.environment.createParameterizedType(generic, arguments, generic.enclosingType());
            for (int i = 0; i < arguments.length; i++) {
                if (variables[i].boundCheck(resolved, arguments[i], null, null) == TypeConstants.BoundCheckStatus.MISMATCH) return null;
            }
            target = resolved;
        }
        // A raw cast must not sneak incompatible generic implementations into the list.
        return target.isCompatibleWith(source) ? target : null;
    }

    private static void infer(TypeBinding template, TypeBinding actual, Map<TypeBinding, TypeBinding> inferred) {
        if (template == null || actual == null) return;
        if (template.isTypeVariable()) {
            inferred.putIfAbsent(template, actual);
        } else if (template instanceof ParameterizedTypeBinding left && actual instanceof ParameterizedTypeBinding right
                && TypeBinding.equalsEquals(left.erasure(), right.erasure()) && left.arguments != null && right.arguments != null) {
            for (int i = 0; i < left.arguments.length; i++) infer(left.arguments[i], right.arguments[i], inferred);
        } else if (template.isArrayType() && actual.isArrayType() && template.dimensions() == actual.dimensions()) {
            infer(template.leafComponentType(), actual.leafComponentType(), inferred);
        }
    }

    private static TypeBinding uncapture(TypeBinding type) {
        if (type instanceof CaptureBinding capture) return capture.wildcard;
        if (type instanceof ParameterizedTypeBinding parameterized && parameterized.arguments != null) {
            return parameterized.environment.createParameterizedType(parameterized.genericType(),
                    Arrays.stream(parameterized.arguments).map(SubtypeCompletion::uncapture).toArray(TypeBinding[]::new), parameterized.enclosingType());
        }
        return type;
    }

    private static final class Lookup extends CompletionRequestor {
        private CompletionContext context;
        private final List<CompletionProposal> proposals = new ArrayList<>();
        Lookup() { setRequireExtendedContext(true); }
        @Override public void acceptContext(CompletionContext context) { this.context = context; }
        @Override public void accept(CompletionProposal proposal) { proposals.add(proposal); }
        @Override public boolean isAllowingRequiredProposals(int proposalKind, int requiredProposalKind) { return true; }
    }
}
