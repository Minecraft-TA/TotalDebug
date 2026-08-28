package com.github.minecraft_ta.totalDebugCompanion.jdt.insight;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionProposal;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionRange;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.tth05.jindex.IndexedClass;
import com.github.tth05.jindex.IndexedField;
import com.github.tth05.jindex.IndexedMethod;
import com.github.tth05.jindex.InnerClassType;
import com.github.tth05.jindex.SearchOptions;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Indexed type and static-member completion using the imports of one Java source file. */
final class IndexedTypeCompletion {
    private IndexedTypeCompletion() {
    }

    static List<DebuggerCompletionProposal> types(
            CompilationUnit unit,
            DebuggerCompletionRange range,
            Set<String> occupiedNames
    ) {
        if (!CompanionClassIndex.isOpen()) {
            return List.of();
        }
        List<TypeCandidate> candidates = Arrays.stream(CompanionClassIndex.get().findClasses(
                        range.prefix(),
                        SearchOptions.with(
                                SearchOptions.SearchMode.PREFIX,
                                SearchOptions.MatchMode.IGNORE_CASE,
                                256
                        )
                ))
                .filter(type -> type.getInnerClassType() == null
                        || type.getInnerClassType() == InnerClassType.MEMBER)
                .map(TypeCandidate::from)
                .filter(candidate -> !candidate.simpleName().isBlank())
                .toList();
        SourceScope scope = SourceScope.from(unit);
        Map<String, List<TypeCandidate>> candidatesBySimpleName = new HashMap<>();
        for (TypeCandidate candidate : candidates) {
            candidatesBySimpleName.computeIfAbsent(candidate.simpleName(), ignored -> new ArrayList<>())
                    .add(candidate);
        }

        List<DebuggerCompletionProposal> proposals = new ArrayList<>(candidates.size());
        for (TypeCandidate candidate : candidates) {
            boolean visible = !occupiedNames.contains(candidate.simpleName())
                    && scope.resolvesSimpleNameTo(candidate, candidatesBySimpleName.get(candidate.simpleName()));
            String insertion = visible ? candidate.simpleName() : candidate.sourceName();
            proposals.add(new DebuggerCompletionProposal(
                    candidate.simpleName(),
                    insertion,
                    DebuggerCompletionProposal.Kind.TYPE,
                    candidate.sourceName(),
                    range.start(),
                    range.end(),
                    insertion.length(),
                    visible ? 45 : 70
            ));
        }
        return List.copyOf(proposals);
    }

    static List<DebuggerCompletionProposal> staticMembers(
            CompilationUnit unit,
            String sourceName,
            DebuggerCompletionRange range
    ) {
        IndexedClass type = resolveType(unit, sourceName);
        if (type == null) {
            return List.of();
        }
        Map<String, DebuggerCompletionProposal> result = new LinkedHashMap<>();
        addMembers(result, type, 20, 30, range, new HashSet<>());
        return List.copyOf(result.values());
    }

    private static IndexedClass resolveType(CompilationUnit unit, String sourceName) {
        if (!CompanionClassIndex.isOpen() || sourceName.isBlank()
                || sourceName.indexOf('(') >= 0 || sourceName.indexOf('[') >= 0) {
            return null;
        }
        String simpleName = sourceName.substring(sourceName.lastIndexOf('.') + 1);
        List<TypeCandidate> candidates = Arrays.stream(CompanionClassIndex.get().findClasses(
                        simpleName,
                        SearchOptions.with(
                                SearchOptions.SearchMode.PREFIX,
                                SearchOptions.MatchMode.MATCH_CASE,
                                128
                        )
                ))
                .filter(type -> Objects.equals(type.getSourceName(), simpleName))
                .map(TypeCandidate::from)
                .toList();
        if (sourceName.indexOf('.') >= 0) {
            return candidates.stream()
                    .filter(candidate -> candidate.sourceName().equals(sourceName))
                    .map(TypeCandidate::type)
                    .findFirst()
                    .orElse(null);
        }
        SourceScope scope = SourceScope.from(unit);
        return candidates.stream()
                .filter(candidate -> scope.resolvesSimpleNameTo(candidate, candidates))
                .map(TypeCandidate::type)
                .findFirst()
                .orElse(null);
    }

    private static void addMembers(
            Map<String, DebuggerCompletionProposal> result,
            IndexedClass type,
            int fieldRank,
            int methodRank,
            DebuggerCompletionRange range,
            Set<String> visited
    ) {
        if (type == null || !visited.add(type.getNameWithPackageDot())) {
            return;
        }
        for (IndexedField field : type.getFields()) {
            if ((field.getAccessFlags() & Opcodes.ACC_STATIC) == 0) {
                continue;
            }
            add(result, new DebuggerCompletionProposal(
                    field.getName(),
                    field.getName(),
                    DebuggerCompletionProposal.Kind.CONSTANT,
                    Type.getType(field.getDescriptorString()).getClassName(),
                    range.start(),
                    range.end(),
                    field.getName().length(),
                    fieldRank
            ));
        }
        for (IndexedMethod method : type.getMethods()) {
            if (method.getName().startsWith("<") || (method.getAccessFlags() & Opcodes.ACC_STATIC) == 0) {
                continue;
            }
            Type methodType = Type.getMethodType(method.getDescriptorString());
            String parameters = Arrays.stream(methodType.getArgumentTypes())
                    .map(Type::getClassName)
                    .collect(java.util.stream.Collectors.joining(", "));
            String insertion = method.getName() + "()";
            add(result, new DebuggerCompletionProposal(
                    method.getName() + "(" + parameters + ")",
                    insertion,
                    DebuggerCompletionProposal.Kind.METHOD,
                    methodType.getReturnType().getClassName(),
                    range.start(),
                    range.end(),
                    insertion.length() - 1,
                    methodRank
            ));
        }
        addMembers(result, type.getSuperClass(), fieldRank + 1, methodRank + 1, range, visited);
        for (IndexedClass interfaceType : type.getInterfaces()) {
            addMembers(result, interfaceType, fieldRank + 1, methodRank + 1, range, visited);
        }
    }

    private static void add(
            Map<String, DebuggerCompletionProposal> result,
            DebuggerCompletionProposal proposal
    ) {
        result.merge(proposal.label(), proposal, (previous, candidate) ->
                candidate.rank() < previous.rank() ? candidate : previous);
    }

    private record TypeCandidate(
            IndexedClass type,
            String simpleName,
            String sourceName,
            String binaryName,
            String packageName
    ) {
        private static TypeCandidate from(IndexedClass type) {
            return new TypeCandidate(
                    type,
                    Objects.requireNonNullElse(type.getSourceName(), ""),
                    type.getNameWithPackageDot().replace('$', '.'),
                    type.getNameWithPackageDot(),
                    type.getPackage().getNameWithParentsDot()
            );
        }
    }

    private record SourceScope(
            String packageName,
            Map<String, String> explicitImports,
            Set<String> packageImports,
            Set<String> staticImports
    ) {
        private static SourceScope from(CompilationUnit unit) {
            String packageName = unit.getPackage() == null
                    ? ""
                    : unit.getPackage().getName().getFullyQualifiedName();
            Map<String, String> explicitImports = new HashMap<>();
            Set<String> packageImports = new HashSet<>();
            Set<String> staticImports = new HashSet<>();
            for (Object value : unit.imports()) {
                ImportDeclaration declaration = (ImportDeclaration) value;
                String importedName = declaration.getName().getFullyQualifiedName();
                if (declaration.isOnDemand()) {
                    (declaration.isStatic() ? staticImports : packageImports).add(importedName);
                } else {
                    explicitImports.put(importedName.substring(importedName.lastIndexOf('.') + 1), importedName);
                }
            }
            return new SourceScope(packageName, explicitImports, packageImports, staticImports);
        }

        private boolean resolvesSimpleNameTo(TypeCandidate candidate, List<TypeCandidate> sameNamedCandidates) {
            String explicitlyImported = this.explicitImports.get(candidate.simpleName());
            if (explicitlyImported != null) {
                return explicitlyImported.equals(candidate.sourceName());
            }
            TypeCandidate samePackage = sameNamedCandidates.stream()
                    .filter(match -> match.packageName().equals(this.packageName))
                    .findFirst()
                    .orElse(null);
            if (samePackage != null) {
                return samePackage.binaryName().equals(candidate.binaryName());
            }
            TypeCandidate javaLang = sameNamedCandidates.stream()
                    .filter(match -> match.packageName().equals("java.lang"))
                    .findFirst()
                    .orElse(null);
            if (javaLang != null) {
                return javaLang.binaryName().equals(candidate.binaryName());
            }
            List<TypeCandidate> imported = sameNamedCandidates.stream()
                    .filter(match -> this.packageImports.contains(match.packageName())
                            || this.staticImports.stream().anyMatch(owner ->
                            match.sourceName().startsWith(owner + ".")))
                    .toList();
            return imported.size() == 1 && imported.getFirst().binaryName().equals(candidate.binaryName());
        }
    }
}
