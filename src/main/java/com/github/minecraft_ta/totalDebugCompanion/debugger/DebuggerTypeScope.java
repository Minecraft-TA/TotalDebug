package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Java package/import scope used by debugger evaluation and completion. */
final class DebuggerTypeScope {
    private final String packageName;
    private final Map<String, String> singleImports;
    private final List<String> onDemandImports;

    private DebuggerTypeScope(
            String packageName,
            Map<String, String> singleImports,
            List<String> onDemandImports
    ) {
        this.packageName = packageName;
        this.singleImports = Map.copyOf(singleImports);
        this.onDemandImports = List.copyOf(onDemandImports);
    }

    static DebuggerTypeScope parse(DebugEngine.Source source) {
        Objects.requireNonNull(source, "source");
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.contents().toCharArray());
        parser.setStatementsRecovery(true);
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);

        String packageName = unit.getPackage() == null
                ? packageName(source.binaryName())
                : unit.getPackage().getName().getFullyQualifiedName();
        Map<String, String> singleImports = new LinkedHashMap<>();
        List<String> onDemandImports = new ArrayList<>();
        for (Object value : unit.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) value;
            String importedName = declaration.getName().getFullyQualifiedName();
            if (declaration.isOnDemand()) {
                onDemandImports.add(importedName);
            } else {
                singleImports.put(simpleName(importedName), importedName);
            }
        }
        return new DebuggerTypeScope(packageName, singleImports, onDemandImports);
    }

    ReferenceType resolve(String sourceName, ReferenceType declaringType, VirtualMachine vm) {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(declaringType, "declaringType");
        Objects.requireNonNull(vm, "vm");
        String normalized = sourceName.replace("...", "[]");
        if (normalized.endsWith("[]") || DebuggerJdiMembers.isPrimitive(normalized)) {
            return null;
        }

        ReferenceType exact = DebuggerJdiMembers.resolveType(normalized, vm);
        if (exact != null) {
            return exact;
        }

        String nestedName = normalized.replace('.', '$');
        for (String enclosing = declaringType.name(); enclosing != null; enclosing = enclosingType(enclosing)) {
            ReferenceType lexical = DebuggerJdiMembers.resolveType(enclosing + "$" + nestedName, vm);
            if (lexical != null) {
                return lexical;
            }
        }

        int separator = normalized.indexOf('.');
        String firstSegment = separator < 0 ? normalized : normalized.substring(0, separator);
        String imported = this.singleImports.get(firstSegment);
        if (imported != null) {
            String suffix = separator < 0 ? "" : normalized.substring(separator);
            ReferenceType importedType = DebuggerJdiMembers.resolveType(imported + suffix, vm);
            if (importedType != null) {
                return importedType;
            }
        }

        ReferenceType samePackage = resolveQualified(this.packageName, normalized, vm);
        if (samePackage != null) {
            return samePackage;
        }
        ReferenceType javaLang = resolveQualified("java.lang", normalized, vm);
        if (javaLang != null) {
            return javaLang;
        }

        Set<ReferenceType> onDemandMatches = new LinkedHashSet<>();
        for (String owner : this.onDemandImports) {
            ReferenceType match = resolveQualified(owner, normalized, vm);
            if (match != null) {
                onDemandMatches.add(match);
            }
        }
        if (onDemandMatches.size() > 1) {
            throw new IllegalArgumentException("Ambiguous type name " + sourceName + ": "
                    + onDemandMatches.stream().map(ReferenceType::name).sorted().toList());
        }
        return onDemandMatches.stream().findFirst().orElse(null);
    }

    List<TypeCandidate> complete(
            String prefix,
            ReferenceType declaringType,
            List<ReferenceType> loadedTypes
    ) {
        String foldedPrefix = Objects.requireNonNull(prefix, "prefix").toLowerCase(Locale.ROOT);
        Map<String, ReferenceType> loadedByName = new LinkedHashMap<>();
        for (ReferenceType type : loadedTypes) {
            loadedByName.putIfAbsent(type.name(), type);
        }
        Map<String, TypeCandidate> candidates = new LinkedHashMap<>();
        for (ReferenceType type : loadedTypes) {
            String binaryName = type.name();
            if (binaryName.startsWith("[") || binaryName.endsWith("package-info")
                    || binaryName.endsWith("module-info")) {
                continue;
            }
            String simpleName = simpleName(binaryName);
            if (simpleName.isBlank() || Character.isDigit(simpleName.charAt(0))
                    || !simpleName.toLowerCase(Locale.ROOT).startsWith(foldedPrefix)) {
                continue;
            }
            ReferenceType visible = resolveLoaded(simpleName, declaringType.name(), loadedByName);
            boolean useSimpleName = visible != null && visible.name().equals(binaryName);
            String insertion = useSimpleName ? simpleName : sourceName(binaryName);
            TypeCandidate candidate = new TypeCandidate(simpleName, insertion, sourceName(binaryName),
                    useSimpleName ? 45 : 70);
            candidates.putIfAbsent(binaryName, candidate);
        }
        return List.copyOf(candidates.values());
    }

    private ReferenceType resolveLoaded(
            String sourceName,
            String declaringBinaryName,
            Map<String, ReferenceType> loadedByName
    ) {
        ReferenceType exact = loadedType(sourceName, loadedByName);
        if (exact != null) {
            return exact;
        }
        String nestedName = sourceName.replace('.', '$');
        for (String enclosing = declaringBinaryName; enclosing != null; enclosing = enclosingType(enclosing)) {
            ReferenceType lexical = loadedType(enclosing + "$" + nestedName, loadedByName);
            if (lexical != null) {
                return lexical;
            }
        }

        int separator = sourceName.indexOf('.');
        String firstSegment = separator < 0 ? sourceName : sourceName.substring(0, separator);
        String imported = this.singleImports.get(firstSegment);
        if (imported != null) {
            String suffix = separator < 0 ? "" : sourceName.substring(separator);
            ReferenceType importedType = loadedType(imported + suffix, loadedByName);
            if (importedType != null) {
                return importedType;
            }
        }

        ReferenceType samePackage = loadedQualified(this.packageName, sourceName, loadedByName);
        if (samePackage != null) {
            return samePackage;
        }
        ReferenceType javaLang = loadedQualified("java.lang", sourceName, loadedByName);
        if (javaLang != null) {
            return javaLang;
        }
        ReferenceType match = null;
        for (String owner : this.onDemandImports) {
            ReferenceType candidate = loadedQualified(owner, sourceName, loadedByName);
            if (candidate != null) {
                if (match != null && !match.name().equals(candidate.name())) {
                    return null;
                }
                match = candidate;
            }
        }
        return match;
    }

    private static ReferenceType loadedQualified(
            String owner,
            String name,
            Map<String, ReferenceType> loadedByName
    ) {
        return loadedType(owner == null || owner.isBlank() ? name : owner + "." + name, loadedByName);
    }

    private static ReferenceType loadedType(String sourceName, Map<String, ReferenceType> loadedByName) {
        ReferenceType exact = loadedByName.get(sourceName);
        if (exact != null) {
            return exact;
        }
        String nestedName = sourceName;
        int dot = nestedName.lastIndexOf('.');
        while (dot > 0) {
            nestedName = nestedName.substring(0, dot) + "$" + nestedName.substring(dot + 1);
            exact = loadedByName.get(nestedName);
            if (exact != null) {
                return exact;
            }
            dot = nestedName.lastIndexOf('.', dot - 1);
        }
        return null;
    }

    private static ReferenceType resolveQualified(String owner, String name, VirtualMachine vm) {
        String qualified = owner == null || owner.isBlank() ? name : owner + "." + name;
        return DebuggerJdiMembers.resolveType(qualified, vm);
    }

    private static String enclosingType(String binaryName) {
        int separator = binaryName.lastIndexOf('$');
        return separator < 0 ? null : binaryName.substring(0, separator);
    }

    private static String packageName(String binaryName) {
        String topLevelName = binaryName.substring(0, binaryName.indexOf('$') < 0
                ? binaryName.length() : binaryName.indexOf('$'));
        int separator = topLevelName.lastIndexOf('.');
        return separator < 0 ? "" : topLevelName.substring(0, separator);
    }

    private static String simpleName(String name) {
        int packageSeparator = name.lastIndexOf('.');
        int nestedSeparator = name.lastIndexOf('$');
        return name.substring(Math.max(packageSeparator, nestedSeparator) + 1);
    }

    private static String sourceName(String binaryName) {
        return binaryName.replace('$', '.');
    }

    record TypeCandidate(String label, String insertion, String detail, int rank) {
    }
}
