package com.github.minecraft_ta.totalDebugCompanion.bytecode.insight;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.HierarchySummary;
import com.github.tth05.jindex.IndexedClass;
import com.github.tth05.jindex.IndexedMethod;
import com.github.tth05.jindex.ReferenceSummary;
import com.github.tth05.jindex.ReferenceTarget;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads editor summaries and hierarchy declarations from one open JIndex snapshot. */
public final class IndexedCodeInsight {
    private final ClassIndex index;

    public IndexedCodeInsight(ClassIndex index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    public Map<CodeSymbol, SymbolInsight> summarize(Collection<CodeSymbol> symbols) {
        Objects.requireNonNull(symbols, "symbols");
        Map<String, IndexedClass> classes = new LinkedHashMap<>();
        Map<String, ClassDetails> details = new LinkedHashMap<>();
        Map<CodeSymbol, SymbolInsight> result = new LinkedHashMap<>();

        for (CodeSymbol symbol : symbols) {
            IndexedClass indexedClass = classes.computeIfAbsent(symbol.ownerClassName(), this::findClass);
            if (indexedClass == null) {
                continue;
            }
            ClassDetails classDetails = details.computeIfAbsent(
                    symbol.ownerClassName(),
                    ignored -> new ClassDetails(indexedClass)
            );
            ReferenceSummary references = this.index.summarizeReferences(toTarget(symbol.referenceQuery()));
            List<HierarchyFacet> hierarchy = new ArrayList<>();
            switch (symbol) {
                case CodeSymbol.ClassSymbol ignored -> addFacet(
                        hierarchy,
                        isInterface(indexedClass)
                                ? HierarchyRelation.IMPLEMENTED_BY
                                : HierarchyRelation.SUBTYPES,
                        classDetails.implementationCount()
                );
                case CodeSymbol.MethodSymbol method -> {
                    int methodIndex = classDetails.methodIndex(method);
                    if (methodIndex < 0) {
                        continue;
                    }
                    IndexedMethod indexedMethod = classDetails.methods()[methodIndex];
                    addFacet(
                            hierarchy,
                            isAbstract(indexedMethod)
                                    ? HierarchyRelation.IMPLEMENTED_BY
                                    : HierarchyRelation.OVERRIDDEN_BY,
                            classDetails.methodImplementationCounts()[methodIndex]
                    );
                    if (classDetails.methodBaseCounts()[methodIndex] > 0) {
                        int implementedBases = 0;
                        int overriddenBases = 0;
                        for (IndexedMethod base : indexedMethod.findBaseMethods()) {
                            if (isAbstract(base)) {
                                implementedBases++;
                            } else {
                                overriddenBases++;
                            }
                        }
                        if (implementedBases + overriddenBases
                                != classDetails.methodBaseCounts()[methodIndex]) {
                            throw new IllegalStateException("JIndex base-method summary changed during lookup");
                        }
                        addFacet(hierarchy, HierarchyRelation.IMPLEMENTS, implementedBases);
                        addFacet(hierarchy, HierarchyRelation.OVERRIDES, overriddenBases);
                    }
                }
                case CodeSymbol.FieldSymbol ignored -> {
                }
            }
            result.put(symbol, new SymbolInsight(references.occurrenceCount(), hierarchy));
        }
        return Map.copyOf(result);
    }

    public HierarchyPage search(HierarchyQuery query, int limit) {
        Objects.requireNonNull(query, "query");
        if (limit < 1) {
            throw new IllegalArgumentException("Hierarchy result limit must be positive");
        }
        IndexedClass owner = findClass(query.symbol().ownerClassName());
        if (owner == null) {
            return new HierarchyPage(List.of(), false);
        }

        List<HierarchyResult> all = switch (query.symbol()) {
            case CodeSymbol.ClassSymbol ignored -> classResults(
                    owner.findImplementations(query.directSubtypesOnly())
            );
            case CodeSymbol.MethodSymbol method -> methodResults(query, owner, method);
            case CodeSymbol.FieldSymbol ignored -> throw new IllegalArgumentException(
                    "Fields do not have implementations"
            );
        };
        all.sort(null);
        boolean truncated = all.size() > limit;
        return new HierarchyPage(truncated ? all.subList(0, limit) : all, truncated);
    }

    private List<HierarchyResult> methodResults(
            HierarchyQuery query,
            IndexedClass owner,
            CodeSymbol.MethodSymbol symbol
    ) {
        IndexedMethod target = findMethod(owner, symbol);
        if (target == null) {
            return new ArrayList<>();
        }
        IndexedMethod[] methods = query.direction() == HierarchyDirection.BASE_METHODS
                ? target.findBaseMethods()
                : target.findImplementations();
        ArrayList<HierarchyResult> results = new ArrayList<>(methods.length);
        for (IndexedMethod method : methods) {
            IndexedClass declaringClass = method.getDeclaringClass();
            results.add(new HierarchyResult(
                    new CodeSymbol.MethodSymbol(
                            declaringClass.getNameWithPackageDot(),
                            method.getName(),
                            method.getDescriptorString()
                    ),
                    declaringClass.getSourceId()
            ));
        }
        return results;
    }

    private static List<HierarchyResult> classResults(IndexedClass[] classes) {
        ArrayList<HierarchyResult> results = new ArrayList<>(classes.length);
        for (IndexedClass indexedClass : classes) {
            results.add(new HierarchyResult(
                    new CodeSymbol.ClassSymbol(indexedClass.getNameWithPackageDot()),
                    indexedClass.getSourceId()
            ));
        }
        return results;
    }

    private IndexedClass findClass(String binaryName) {
        String[] parts = CodeUtils.splitTypeName(binaryName);
        return this.index.findClass(parts[0], parts[1]);
    }

    private static IndexedMethod findMethod(IndexedClass owner, CodeSymbol.MethodSymbol target) {
        for (IndexedMethod method : owner.getMethods()) {
            if (method.getName().equals(target.name())
                    && method.getDescriptorString().equals(target.descriptor())) {
                return method;
            }
        }
        return null;
    }

    private static ReferenceTarget toTarget(ReferenceQuery query) {
        return switch (query) {
            case ReferenceQuery.ClassReference type ->
                    ReferenceTarget.classTarget(internalName(type.className()));
            case ReferenceQuery.FieldReference field -> ReferenceTarget.fieldTarget(
                    internalName(field.ownerClassName()), field.name(), field.descriptor()
            );
            case ReferenceQuery.MethodReference method -> ReferenceTarget.methodTarget(
                    internalName(method.ownerClassName()), method.name(), method.descriptor()
            );
        };
    }

    private static String internalName(String binaryName) {
        return binaryName.replace('.', '/');
    }

    private static void addFacet(List<HierarchyFacet> facets, HierarchyRelation relation, int count) {
        if (count > 0) {
            facets.add(new HierarchyFacet(relation, count));
        }
    }

    private static boolean isInterface(IndexedClass indexedClass) {
        return (indexedClass.getAccessFlags() & Opcodes.ACC_INTERFACE) != 0;
    }

    private static boolean isAbstract(IndexedMethod method) {
        return (method.getAccessFlags() & Opcodes.ACC_ABSTRACT) != 0;
    }

    private record ClassDetails(
            IndexedMethod[] methods,
            int implementationCount,
            int[] methodImplementationCounts,
            int[] methodBaseCounts
    ) {
        private ClassDetails(IndexedClass indexedClass) {
            this(indexedClass.getMethods(), indexedClass.summarizeHierarchy());
        }

        private ClassDetails(IndexedMethod[] methods, HierarchySummary hierarchy) {
            this(
                    methods,
                    hierarchy.implementationCount(),
                    hierarchy.methodImplementationCounts(),
                    hierarchy.methodBaseCounts()
            );
            if (this.methods.length != this.methodImplementationCounts.length) {
                throw new IllegalStateException("JIndex hierarchy summary does not match declared methods");
            }
        }

        private int methodIndex(CodeSymbol.MethodSymbol target) {
            for (int index = 0; index < this.methods.length; index++) {
                IndexedMethod method = this.methods[index];
                if (method.getName().equals(target.name())
                        && method.getDescriptorString().equals(target.descriptor())) {
                    return index;
                }
            }
            return -1;
        }
    }
}
