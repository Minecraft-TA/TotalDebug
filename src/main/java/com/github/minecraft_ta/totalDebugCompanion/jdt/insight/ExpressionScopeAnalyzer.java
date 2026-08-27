package com.github.minecraft_ta.totalDebugCompanion.jdt.insight;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionProposal;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionRange;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Source/JDT-backed completion for a breakpoint condition before a target is paused. */
public final class ExpressionScopeAnalyzer {
    private static final List<String> KEYWORDS = List.of("true", "false", "null", "this", "super");

    private ExpressionScopeAnalyzer() {
    }

    public static List<DebuggerCompletionProposal> analyze(CompilationUnit unit, int sourceOffset) {
        Objects.requireNonNull(unit, "unit");
        if (sourceOffset < 0 || sourceOffset > unit.getLength()) {
            throw new IllegalArgumentException("sourceOffset is outside the compilation unit");
        }
        ASTNode selected = NodeFinder.perform(unit, sourceOffset, 0);
        MethodDeclaration method = ancestor(selected, MethodDeclaration.class);
        AbstractTypeDeclaration type = ancestor(selected, AbstractTypeDeclaration.class);
        if (method == null || type == null) return keywordProposals(0, 0);

        Map<String, DebuggerCompletionProposal> proposals = new LinkedHashMap<>();
        boolean staticContext = Modifier.isStatic(method.getModifiers());
        if (!staticContext) {
            add(proposals, proposal("this", type.getName().getIdentifier(),
                    DebuggerCompletionProposal.Kind.KEYWORD, 80));
        }
        addTypeMembers(proposals, type, staticContext, 25, 35, null, new java.util.HashSet<>());
        for (Object declaration : method.parameters()) {
            add(proposals, variable((SingleVariableDeclaration) declaration, 10));
        }
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment fragment) {
                if (fragment.getParent() instanceof FieldDeclaration) return true;
                if (isVisible(fragment, sourceOffset, method)) {
                    add(proposals, new DebuggerCompletionProposal(
                            fragment.getName().getIdentifier(), fragment.getName().getIdentifier(),
                            DebuggerCompletionProposal.Kind.VARIABLE, variableType(fragment),
                            0, 0, fragment.getName().getLength(), 10));
                }
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration declaration) {
                if (!method.parameters().contains(declaration)
                        && isVisible(declaration, sourceOffset, method)) {
                    add(proposals, variable(declaration, 10));
                }
                return true;
            }
        });
        keywordProposals(0, 0).forEach(proposal -> add(proposals, proposal));
        return sorted(proposals);
    }

    public static List<DebuggerCompletionProposal> complete(
            CompilationUnit unit,
            int sourceOffset,
            String expression,
            int caret
    ) {
        Objects.requireNonNull(expression, "expression");
        DebuggerCompletionRange range = DebuggerCompletionRange.around(expression, caret);
        if (!range.memberAccess()) {
            return analyze(unit, sourceOffset).stream()
                    .map(proposal -> proposal.withRange(range.start(), range.end()))
                    .filter(proposal -> startsWith(proposal.label(), range.prefix()))
                    .toList();
        }
        String owner = expression.substring(0, range.ownerEnd()).trim();
        ASTNode selected = NodeFinder.perform(unit, sourceOffset, 0);
        AbstractTypeDeclaration type = ancestor(selected, AbstractTypeDeclaration.class);
        Map<String, DebuggerCompletionProposal> members = new LinkedHashMap<>();
        if (type != null) addMemberProposals(members, type, owner, sourceOffset, range);
        return sorted(members).stream()
                .filter(proposal -> startsWith(proposal.label(), range.prefix()))
                .toList();
    }

    private static void addMemberProposals(Map<String, DebuggerCompletionProposal> result,
                                           AbstractTypeDeclaration type, String owner, int sourceOffset,
                                           DebuggerCompletionRange range) {
        boolean staticOwner = owner.equals(type.getName().getIdentifier());
        AbstractTypeDeclaration ownerType = resolveOwnerType(type, owner, sourceOffset);
        if (owner.equals("super") && ownerType != null) staticOwner = false;
        if (ownerType == null) return;
        addTypeMembers(result, ownerType, staticOwner, 20, 30, range, new java.util.HashSet<>());
    }

    private static AbstractTypeDeclaration resolveOwnerType(
            AbstractTypeDeclaration type, String owner, int sourceOffset) {
        if (owner.equals("this")) return type;
        if (owner.equals("super")) {
            if (type instanceof TypeDeclaration declaration && declaration.getSuperclassType() != null) {
                return findType(type.getRoot(), declaration.getSuperclassType().toString());
            }
            return null;
        }
        if (owner.equals(type.getName().getIdentifier())) return type;
        int call = owner.lastIndexOf("(");
        if (call > 0 && owner.endsWith(")")) {
            int dot = owner.lastIndexOf('.', call);
            if (dot > 0) {
                AbstractTypeDeclaration receiver = resolveOwnerType(type, owner.substring(0, dot), sourceOffset);
                String methodName = owner.substring(dot + 1, call);
                if (receiver != null) {
                    for (Object body : receiver.bodyDeclarations()) {
                        if (body instanceof MethodDeclaration method
                                && method.getName().getIdentifier().equals(methodName)
                                && method.getReturnType2() != null) {
                            return findType(type.getRoot(), method.getReturnType2().toString());
                        }
                    }
                }
            }
        }
        int dot = owner.lastIndexOf('.');
        String simple = dot < 0 ? owner : owner.substring(dot + 1);
        AbstractTypeDeclaration declaration = declarationForVariable(type, sourceOffset, simple);
        if (declaration != null) return declaration;
        return findType(type.getRoot(), owner);
    }

    private static void addTypeMembers(Map<String, DebuggerCompletionProposal> result,
                                       AbstractTypeDeclaration type, boolean staticOnly,
                                       int fieldRank, int methodRank, DebuggerCompletionRange range,
                                       java.util.Set<String> visited) {
        if (type == null || !visited.add(type.getName().getFullyQualifiedName())) return;
        addDeclaredFields(result, type, staticOnly, fieldRank, range);
        addDeclaredMethods(result, type, staticOnly, methodRank, range);
        if (type instanceof TypeDeclaration declaration) {
            if (declaration.getSuperclassType() != null) {
                addTypeMembers(result, findType(type.getRoot(), declaration.getSuperclassType().toString()),
                        staticOnly, fieldRank + 1, methodRank + 1, range, visited);
            }
            for (Object interfaceType : declaration.superInterfaceTypes()) {
                addTypeMembers(result, findType(type.getRoot(), interfaceType.toString()),
                        staticOnly, fieldRank + 1, methodRank + 1, range, visited);
            }
        }
    }

    private static AbstractTypeDeclaration declarationForVariable(
            AbstractTypeDeclaration type, int sourceOffset, String name) {
        MethodDeclaration method = ancestor(NodeFinder.perform(type.getRoot(), sourceOffset, 0), MethodDeclaration.class);
        if (method == null) return null;
        final AbstractTypeDeclaration[] resolved = {null};
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment fragment) {
                if (fragment.getName().getIdentifier().equals(name)) {
                    AbstractTypeDeclaration declaration = findType(type.getRoot(), variableType(fragment));
                    if (declaration != null) resolved[0] = declaration;
                }
                return true;
            }
        });
        return resolved[0];
    }

    private static AbstractTypeDeclaration findType(ASTNode root, String name) {
        String simple = name.substring(name.lastIndexOf('.') + 1).replace("[]", "");
        final AbstractTypeDeclaration[] found = {null};
        root.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration declaration) {
                if (declaration.getName().getIdentifier().equals(simple)) found[0] = declaration;
                return found[0] == null;
            }
        });
        return found[0];
    }

    private static void addDeclaredFields(Map<String, DebuggerCompletionProposal> result,
                                          AbstractTypeDeclaration type, boolean staticOnly, int rank,
                                          DebuggerCompletionRange range) {
        for (Object body : type.bodyDeclarations()) {
            if (!(body instanceof FieldDeclaration field)
                    || staticOnly && !Modifier.isStatic(field.getModifiers())) continue;
            for (Object fragmentObject : field.fragments()) {
                VariableDeclarationFragment fragment = (VariableDeclarationFragment) fragmentObject;
                add(result, proposal(fragment.getName().getIdentifier(), field.getType().toString(),
                        Modifier.isStatic(field.getModifiers())
                                ? DebuggerCompletionProposal.Kind.CONSTANT : DebuggerCompletionProposal.Kind.FIELD,
                        rank, range));
            }
        }
    }

    private static void addDeclaredMethods(Map<String, DebuggerCompletionProposal> result,
                                           AbstractTypeDeclaration type, boolean staticOnly, int rank,
                                           DebuggerCompletionRange range) {
        for (Object body : type.bodyDeclarations()) {
            if (!(body instanceof MethodDeclaration method)
                    || staticOnly && !Modifier.isStatic(method.getModifiers())) continue;
            String insertion = method.getName().getIdentifier() + "()";
            StringBuilder parameters = new StringBuilder();
            for (Object parameter : method.parameters()) {
                if (!parameters.isEmpty()) parameters.append(", ");
                parameters.append(parameter);
            }
            add(result, new DebuggerCompletionProposal(
                    method.getName() + "(" + parameters + ")", insertion, DebuggerCompletionProposal.Kind.METHOD,
                    method.getReturnType2() == null ? "void" : method.getReturnType2().toString(),
                    range == null ? 0 : range.start(), range == null ? 0 : range.end(), insertion.length() - 1, rank));
        }
    }

    private static DebuggerCompletionProposal variable(SingleVariableDeclaration declaration, int rank) {
        String type = declaration.getType() + (declaration.isVarargs() ? "..." : "");
        return new DebuggerCompletionProposal(declaration.getName().getIdentifier(),
                declaration.getName().getIdentifier(), DebuggerCompletionProposal.Kind.VARIABLE,
                type, 0, 0, declaration.getName().getLength(), rank);
    }

    private static DebuggerCompletionProposal proposal(String name, String detail,
                                                        DebuggerCompletionProposal.Kind kind, int rank) {
        return proposal(name, detail, kind, rank, null);
    }

    private static DebuggerCompletionProposal proposal(String name, String detail,
                                                        DebuggerCompletionProposal.Kind kind, int rank,
                                                        DebuggerCompletionRange range) {
        int start = range == null ? 0 : range.start();
        int end = range == null ? 0 : range.end();
        return new DebuggerCompletionProposal(name, name, kind, detail, start, end, name.length(), rank);
    }

    private static void add(Map<String, DebuggerCompletionProposal> result,
                             DebuggerCompletionProposal proposal) {
        result.merge(proposal.label(), proposal, (oldValue, newValue) ->
                newValue.rank() < oldValue.rank() ? newValue : oldValue);
    }

    private static List<DebuggerCompletionProposal> sorted(Map<String, DebuggerCompletionProposal> proposals) {
        return proposals.values().stream()
                .sorted(Comparator.comparingInt(DebuggerCompletionProposal::rank)
                        .thenComparing(DebuggerCompletionProposal::label, String.CASE_INSENSITIVE_ORDER))
                .limit(64)
                .toList();
    }

    private static List<DebuggerCompletionProposal> keywordProposals(int start, int end) {
        return KEYWORDS.stream().map(keyword -> new DebuggerCompletionProposal(
                keyword, keyword, DebuggerCompletionProposal.Kind.KEYWORD,
                keyword.equals("null") ? "null literal" : "Java expression keyword",
                start, end, keyword.length(), 80)).toList();
    }

    private static boolean startsWith(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean isVisible(ASTNode declaration, int offset, MethodDeclaration method) {
        int visibleFrom = declaration.getStartPosition() + declaration.getLength();
        if (offset < visibleFrom) return false;
        ASTNode scope = declaration.getParent();
        while (scope != null && scope != method) {
            if (scope instanceof Block) return offset < scope.getStartPosition() + scope.getLength();
            scope = scope.getParent();
        }
        return scope == method && offset < method.getStartPosition() + method.getLength();
    }

    private static String variableType(VariableDeclarationFragment fragment) {
        return switch (fragment.getParent()) {
            case VariableDeclarationStatement statement -> statement.getType().toString();
            case VariableDeclarationExpression expression -> expression.getType().toString();
            default -> "";
        };
    }

    private static <T extends ASTNode> T ancestor(ASTNode start, Class<T> type) {
        ASTNode current = start;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getParent();
        }
        return null;
    }
}
