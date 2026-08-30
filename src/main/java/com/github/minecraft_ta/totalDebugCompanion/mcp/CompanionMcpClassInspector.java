package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.decompile.CompanionDecompilationService;
import com.github.minecraft_ta.totalDebugCompanion.decompile.DecompiledSource;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexedClass;
import com.github.tth05.jindex.IndexedField;
import com.github.tth05.jindex.IndexedMethod;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

final class CompanionMcpClassInspector {
    private final RuntimeClassAccess runtimeClasses;
    private final Supplier<ClassIndex> classIndex;

    CompanionMcpClassInspector(
            Supplier<CompanionDecompilationService> decompilationService,
            Supplier<ClassIndex> classIndex
    ) {
        this(new RuntimeClassAccess() {
            @Override
            public DecompiledSource source(String binaryName) {
                return Objects.requireNonNull(decompilationService.get(), "decompilationService returned null")
                        .load(binaryName)
                        .join();
            }

            @Override
            public byte[] bytecode(String binaryName) throws IOException {
                return Objects.requireNonNull(decompilationService.get(), "decompilationService returned null")
                        .loadClassBytes(binaryName);
            }

            @Override
            public RuntimeSnapshotBytecodeSource.ClassOrigin origin(String binaryName) {
                return Objects.requireNonNull(decompilationService.get(), "decompilationService returned null")
                        .findClassOrigin(binaryName);
            }
        }, classIndex);
    }

    CompanionMcpClassInspector(RuntimeClassAccess runtimeClasses, Supplier<ClassIndex> classIndex) {
        this.runtimeClasses = Objects.requireNonNull(runtimeClasses, "runtimeClasses");
        this.classIndex = Objects.requireNonNull(classIndex, "classIndex");
    }

    Map<String, Object> source(String binaryName) {
        DecompiledSource source = this.runtimeClasses.source(requireBinaryName(binaryName));
        if (source == null) {
            throw unknownClass(binaryName);
        }
        return Map.of("source", source.contents());
    }

    Map<String, Object> bytecode(String binaryName) throws IOException {
        byte[] bytes = this.runtimeClasses.bytecode(requireBinaryName(binaryName));
        if (bytes == null) {
            throw unknownClass(binaryName);
        }
        StringWriter output = new StringWriter();
        new ClassReader(bytes).accept(new TraceClassVisitor(new PrintWriter(output)), 0);
        return Map.of("bytecode", output.toString());
    }

    Map<String, Object> origin(String binaryName) {
        RuntimeSnapshotBytecodeSource.ClassOrigin origin = this.runtimeClasses.origin(requireBinaryName(binaryName));
        if (origin == null) {
            throw unknownClass(binaryName);
        }
        return Map.of(
                "logical_source", origin.logicalSource(),
                "resource", origin.resourceName(),
                "module", Map.of(
                        "id", origin.module().id(),
                        "name", origin.module().displayName()
                )
        );
    }

    Map<String, Object> members(String binaryName) {
        IndexedClass indexedClass = Objects.requireNonNull(this.classIndex.get(), "classIndex returned null")
                .findClass(requireBinaryName(binaryName));
        if (indexedClass == null) {
            throw unknownClass(binaryName);
        }

        List<Map<String, Object>> fields = new ArrayList<>();
        for (IndexedField field : indexedClass.getFields()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", field.getName());
            value.put("descriptor", field.getDescriptorString());
            putIfPresent(value, "signature", field.getGenericSignatureString());
            putIfNotEmpty(value, "modifiers", fieldModifiers(field.getAccessFlags()));
            fields.add(value);
        }

        List<Map<String, Object>> methods = new ArrayList<>();
        for (IndexedMethod method : indexedClass.getMethods()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", method.getName());
            value.put("descriptor", method.getDescriptorString());
            putIfPresent(value, "signature", method.getGenericSignatureString());
            putIfNotEmpty(value, "modifiers", methodModifiers(method.getAccessFlags()));
            List<String> exceptions = java.util.Arrays.stream(method.getExceptions())
                    .map(IndexedClass::getNameWithPackageDot)
                    .toList();
            putIfNotEmpty(value, "exceptions", exceptions);
            methods.add(value);
        }
        return Map.of("fields", fields, "methods", methods);
    }

    private static List<String> fieldModifiers(int flags) {
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

    private static List<String> methodModifiers(int flags) {
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

    private static void putIfPresent(Map<String, Object> value, String key, String item) {
        if (item != null) {
            value.put(key, item);
        }
    }

    private static void putIfNotEmpty(Map<String, Object> value, String key, List<?> items) {
        if (!items.isEmpty()) {
            value.put(key, items);
        }
    }

    private static String requireBinaryName(String binaryName) {
        if (binaryName == null || binaryName.isBlank()
                || binaryName.indexOf('/') >= 0
                || binaryName.indexOf('\\') >= 0
                || binaryName.endsWith(".class")) {
            throw new IllegalArgumentException("binary_name must be a Java binary name");
        }
        return binaryName;
    }

    private static IllegalArgumentException unknownClass(String binaryName) {
        return new IllegalArgumentException("Class not found: " + binaryName);
    }

    interface RuntimeClassAccess {
        DecompiledSource source(String binaryName);

        byte[] bytecode(String binaryName) throws IOException;

        RuntimeSnapshotBytecodeSource.ClassOrigin origin(String binaryName);
    }
}
