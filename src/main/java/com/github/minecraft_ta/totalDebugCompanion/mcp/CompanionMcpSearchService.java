package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeInventory;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexedClass;
import com.github.tth05.jindex.IndexedField;
import com.github.tth05.jindex.IndexedMethod;
import com.github.tth05.jindex.LiteralSearchPage;
import com.github.tth05.jindex.LiteralSearchResult;
import com.github.tth05.jindex.ReferenceResult;
import com.github.tth05.jindex.ReferenceSearchPage;
import com.github.tth05.jindex.ReferenceTarget;
import com.github.tth05.jindex.SearchOptions;
import com.github.tth05.jindex.SymbolKind;
import com.github.tth05.jindex.SymbolSearchResult;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.Supplier;

final class CompanionMcpSearchService {
    static final int RESULT_LIMIT = 100;
    private static final int PROBE_LIMIT = RESULT_LIMIT + 1;
    private static final SearchOptions CONTAINS = SearchOptions.with(
            SearchOptions.SearchMode.CONTAINS,
            SearchOptions.MatchMode.IGNORE_CASE,
            PROBE_LIMIT
    );

    private final Supplier<ClassIndex> classIndex;
    private final IntFunction<RuntimeInventory.RuntimeModule> moduleLookup;

    CompanionMcpSearchService(
            Supplier<ClassIndex> classIndex,
            IntFunction<RuntimeInventory.RuntimeModule> moduleLookup
    ) {
        this.classIndex = Objects.requireNonNull(classIndex, "classIndex");
        this.moduleLookup = Objects.requireNonNull(moduleLookup, "moduleLookup");
    }

    Map<String, Object> searchClasses(String query) {
        String checkedQuery = requireText(query, "query");
        ClassIndex index = index();
        IndexedClass exact = index.findClass(checkedQuery);
        if (exact != null) {
            return Map.of("classes", List.of(describeClass(exact)));
        }

        IndexedClass[] matches = index.findClassesByBinaryName(checkedQuery, CONTAINS);
        return boundedList("classes", java.util.Arrays.stream(matches)
                .limit(RESULT_LIMIT)
                .map(this::describeClass)
                .toList(), matches.length > RESULT_LIMIT);
    }

    Map<String, Object> searchSymbols(String query, String owner) {
        String checkedQuery = optionalText(query, "query");
        String checkedOwner = optionalText(owner, "owner");
        if (checkedQuery == null && checkedOwner == null) {
            throw new IllegalArgumentException("search_symbols requires query or owner");
        }
        if (checkedOwner != null) {
            return ownedSymbols(checkedOwner, checkedQuery);
        }

        SymbolSearchResult[] matches = index().findSymbols(
                checkedQuery,
                CONTAINS,
                EnumSet.of(SymbolKind.FIELD, SymbolKind.METHOD)
        );
        return boundedList("symbols", java.util.Arrays.stream(matches)
                .limit(RESULT_LIMIT)
                .map(this::describeSymbol)
                .toList(), matches.length > RESULT_LIMIT);
    }

    Map<String, Object> findUsages(Map<String, Object> target) {
        Objects.requireNonNull(target, "target");
        String kind = requireText(target.get("kind"), "target.kind").toLowerCase(Locale.ROOT);
        String owner = requireBinaryName(requireText(target.get("owner"), "target.owner"), "target.owner");
        ReferenceTarget referenceTarget = switch (kind) {
            case "class" -> ReferenceTarget.classTarget(internalName(owner));
            case "field" -> ReferenceTarget.fieldTarget(
                    internalName(owner),
                    requireText(target.get("name"), "target.name"),
                    requireText(target.get("descriptor"), "target.descriptor")
            );
            case "method" -> ReferenceTarget.methodTarget(
                    internalName(owner),
                    requireText(target.get("name"), "target.name"),
                    requireText(target.get("descriptor"), "target.descriptor")
            );
            default -> throw new IllegalArgumentException("target.kind must be class, field, or method");
        };
        ReferenceSearchPage page = index().findReferences(referenceTarget, RESULT_LIMIT);
        List<Map<String, Object>> usages = java.util.Arrays.stream(page.results())
                .map(this::describeUsage)
                .toList();
        return boundedList("usages", usages, page.truncated());
    }

    Map<String, Object> searchLiterals(String query) {
        LiteralSearchPage page = index().findLiteralsContaining(requireText(query, "query"), RESULT_LIMIT);
        List<Map<String, Object>> literals = java.util.Arrays.stream(page.results())
                .map(this::describeLiteral)
                .toList();
        return boundedList("literals", literals, page.truncated());
    }

    private Map<String, Object> ownedSymbols(String owner, String query) {
        IndexedClass indexedClass = index().findClass(requireBinaryName(owner, "owner"));
        if (indexedClass == null) {
            throw new IllegalArgumentException("Class not found: " + owner);
        }
        String normalizedQuery = query == null ? null : query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> symbols = new ArrayList<>();
        boolean truncated = false;
        for (IndexedField field : indexedClass.getFields()) {
            if (matches(field.getName(), normalizedQuery)) {
                if (symbols.size() == RESULT_LIMIT) {
                    truncated = true;
                    break;
                }
                symbols.add(describeField(indexedClass, field));
            }
        }
        if (!truncated) {
            for (IndexedMethod method : indexedClass.getMethods()) {
                if (matches(method.getName(), normalizedQuery)) {
                    if (symbols.size() == RESULT_LIMIT) {
                        truncated = true;
                        break;
                    }
                    symbols.add(describeMethod(indexedClass, method));
                }
            }
        }
        return boundedList("symbols", symbols, truncated);
    }

    private Map<String, Object> describeClass(IndexedClass indexedClass) {
        int flags = indexedClass.getAccessFlags();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("binary_name", indexedClass.getNameWithPackageDot());
        value.put("kind", classKind(flags));
        putModifiers(value, classModifiers(flags));
        value.put("module", module(indexedClass.getSourceId()));
        return value;
    }

    private Map<String, Object> describeSymbol(SymbolSearchResult symbol) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", symbol.kind().name().toLowerCase(Locale.ROOT));
        value.put("owner", binaryName(symbol.ownerInternalName()));
        value.put("name", symbol.name());
        value.put("descriptor", symbol.descriptor());
        putModifiers(value, symbol.kind() == SymbolKind.FIELD
                ? fieldModifiers(symbol.accessFlags())
                : methodModifiers(symbol.accessFlags()));
        value.put("module", module(symbol.sourceId()));
        return value;
    }

    private Map<String, Object> describeField(IndexedClass owner, IndexedField field) {
        return describeOwnedSymbol(
                "field",
                owner,
                field.getName(),
                field.getDescriptorString(),
                fieldModifiers(field.getAccessFlags())
        );
    }

    private Map<String, Object> describeMethod(IndexedClass owner, IndexedMethod method) {
        return describeOwnedSymbol(
                "method",
                owner,
                method.getName(),
                method.getDescriptorString(),
                methodModifiers(method.getAccessFlags())
        );
    }

    private Map<String, Object> describeOwnedSymbol(
            String kind,
            IndexedClass owner,
            String name,
            String descriptor,
            List<String> modifiers
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", kind);
        value.put("owner", owner.getNameWithPackageDot());
        value.put("name", name);
        value.put("descriptor", descriptor);
        putModifiers(value, modifiers);
        value.put("module", module(owner.getSourceId()));
        return value;
    }

    private Map<String, Object> describeUsage(ReferenceResult usage) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", usage.kind().name().toLowerCase(Locale.ROOT));
        value.put("owner", binaryName(usage.ownerInternalName()));
        if (!usage.name().isEmpty()) {
            value.put("name", usage.name());
        }
        if (!usage.descriptor().isEmpty()) {
            value.put("descriptor", usage.descriptor());
        }
        value.put("relationships", usage.kinds().stream()
                .map(kind -> kind.name().toLowerCase(Locale.ROOT))
                .sorted()
                .toList());
        value.put("occurrences", usage.occurrenceCount());
        value.put("module", module(usage.sourceId()));
        return value;
    }

    private Map<String, Object> describeLiteral(LiteralSearchResult literal) {
        Map<String, Map<String, Object>> modules = new LinkedHashMap<>();
        for (int sourceId : literal.sourceIds()) {
            Map<String, Object> module = module(sourceId);
            modules.putIfAbsent((String) module.get("id"), module);
        }
        return Map.of("value", literal.value(), "modules", List.copyOf(modules.values()));
    }

    private ClassIndex index() {
        return Objects.requireNonNull(this.classIndex.get(), "The runtime class index is unavailable");
    }

    private Map<String, Object> module(int sourceId) {
        RuntimeInventory.RuntimeModule module = Objects.requireNonNull(
                this.moduleLookup.apply(sourceId),
                "No runtime module for source " + sourceId
        );
        return Map.of(
                "id", module.id(),
                "name", module.displayName(),
                "kind", module.kind().name().toLowerCase(Locale.ROOT)
        );
    }

    static String classKind(int flags) {
        if ((flags & Opcodes.ACC_ANNOTATION) != 0) {
            return "annotation";
        }
        if ((flags & Opcodes.ACC_ENUM) != 0) {
            return "enum";
        }
        if ((flags & Opcodes.ACC_RECORD) != 0) {
            return "record";
        }
        if ((flags & Opcodes.ACC_INTERFACE) != 0) {
            return "interface";
        }
        if ((flags & Opcodes.ACC_MODULE) != 0) {
            return "module";
        }
        return "class";
    }

    static List<String> classModifiers(int flags) {
        List<String> modifiers = accessModifiers(flags);
        addModifier(modifiers, flags, Opcodes.ACC_ABSTRACT, "abstract");
        addModifier(modifiers, flags, Opcodes.ACC_STATIC, "static");
        addModifier(modifiers, flags, Opcodes.ACC_FINAL, "final");
        addModifier(modifiers, flags, Opcodes.ACC_SYNTHETIC, "synthetic");
        addModifier(modifiers, flags, Opcodes.ACC_DEPRECATED, "deprecated");
        return List.copyOf(modifiers);
    }

    static List<String> fieldModifiers(int flags) {
        List<String> modifiers = accessModifiers(flags);
        addModifier(modifiers, flags, Opcodes.ACC_STATIC, "static");
        addModifier(modifiers, flags, Opcodes.ACC_FINAL, "final");
        addModifier(modifiers, flags, Opcodes.ACC_VOLATILE, "volatile");
        addModifier(modifiers, flags, Opcodes.ACC_TRANSIENT, "transient");
        addModifier(modifiers, flags, Opcodes.ACC_SYNTHETIC, "synthetic");
        addModifier(modifiers, flags, Opcodes.ACC_ENUM, "enum");
        addModifier(modifiers, flags, Opcodes.ACC_DEPRECATED, "deprecated");
        return List.copyOf(modifiers);
    }

    static List<String> methodModifiers(int flags) {
        List<String> modifiers = accessModifiers(flags);
        addModifier(modifiers, flags, Opcodes.ACC_STATIC, "static");
        addModifier(modifiers, flags, Opcodes.ACC_FINAL, "final");
        addModifier(modifiers, flags, Opcodes.ACC_SYNCHRONIZED, "synchronized");
        addModifier(modifiers, flags, Opcodes.ACC_BRIDGE, "bridge");
        addModifier(modifiers, flags, Opcodes.ACC_VARARGS, "varargs");
        addModifier(modifiers, flags, Opcodes.ACC_NATIVE, "native");
        addModifier(modifiers, flags, Opcodes.ACC_ABSTRACT, "abstract");
        addModifier(modifiers, flags, Opcodes.ACC_STRICT, "strictfp");
        addModifier(modifiers, flags, Opcodes.ACC_SYNTHETIC, "synthetic");
        addModifier(modifiers, flags, Opcodes.ACC_DEPRECATED, "deprecated");
        return List.copyOf(modifiers);
    }

    private static List<String> accessModifiers(int flags) {
        List<String> modifiers = new ArrayList<>();
        addModifier(modifiers, flags, Opcodes.ACC_PUBLIC, "public");
        addModifier(modifiers, flags, Opcodes.ACC_PROTECTED, "protected");
        addModifier(modifiers, flags, Opcodes.ACC_PRIVATE, "private");
        return modifiers;
    }

    private static void addModifier(List<String> modifiers, int flags, int mask, String name) {
        if ((flags & mask) != 0) {
            modifiers.add(name);
        }
    }

    private static void putModifiers(Map<String, Object> value, List<String> modifiers) {
        if (!modifiers.isEmpty()) {
            value.put("modifiers", modifiers);
        }
    }

    private static boolean matches(String value, String normalizedQuery) {
        return normalizedQuery == null || value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private static String optionalText(Object value, String name) {
        if (value == null) {
            return null;
        }
        return requireText(value, name);
    }

    private static String requireText(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return text;
    }

    private static String requireBinaryName(String value, String name) {
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.endsWith(".class")) {
            throw new IllegalArgumentException(name + " must be a Java binary name");
        }
        return value;
    }

    private static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    private static String internalName(String binaryName) {
        return binaryName.replace('.', '/');
    }

    private static Map<String, Object> boundedList(String key, List<Map<String, Object>> values, boolean truncated) {
        if (!truncated) {
            return Map.of(key, values);
        }
        return Map.of(key, values, "truncated", true);
    }
}
