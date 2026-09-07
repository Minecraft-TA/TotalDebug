package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.decompile.CompanionDecompilationService;
import com.github.minecraft_ta.totalDebugCompanion.decompile.DecompiledSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Returns exact source scopes from runtime classes. */
final class CompanionMcpRuntimeSource {
    private static final String COMPILATION_UNIT_NAME = "RuntimeSource";

    private final RuntimeClassAccess runtimeClasses;

    CompanionMcpRuntimeSource(Supplier<CompanionDecompilationService> decompilationService) {
        this(binaryName -> Objects.requireNonNull(
                        decompilationService.get(),
                        "decompilationService returned null"
                )
                .load(binaryName)
                .join());
    }

    CompanionMcpRuntimeSource(RuntimeClassAccess runtimeClasses) {
        this.runtimeClasses = Objects.requireNonNull(runtimeClasses, "runtimeClasses");
    }

    Map<String, Object> source(Map<String, Object> requestedTarget) {
        SourceTarget target = SourceTarget.parse(requestedTarget);
        DecompiledSource decompiled = this.runtimeClasses.source(target.binaryName());
        if (decompiled == null) {
            throw new IllegalArgumentException("Class not found: " + target.binaryName());
        }

        String contents = decompiled.contents();
        CompilationUnit unit = ASTCache.rawParse(COMPILATION_UNIT_NAME, contents);
        AbstractTypeDeclaration type = findType(unit, decompiled.binaryName(), target.binaryName());
        ASTNode scope = switch (target.kind()) {
            case "class" -> type;
            case "field" -> findField(type, target);
            case "method" -> findMethod(type, target);
            case "record_component" -> findRecordComponent(type, target);
            default -> throw new IllegalStateException("Unsupported source target: " + target.kind());
        };

        int start = scope.getStartPosition();
        int length = scope.getLength();
        if (start < 0 || length < 1 || start + length > contents.length()) {
            throw new IllegalStateException("The decompiled source contains an invalid scope range");
        }
        int startLine = unit.getLineNumber(start);
        if (startLine < 1) {
            throw new IllegalStateException("The decompiled source has no line for the requested scope");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", target.asMap());
        if (unit.getPackage() != null) {
            result.put("package", unit.getPackage().getName().getFullyQualifiedName());
        }
        result.put("imports", imports(unit));
        result.put("start_line", startLine);
        result.put("source", contents.substring(start, start + length));
        return result;
    }

    private static AbstractTypeDeclaration findType(
            CompilationUnit unit,
            String decompiledBinaryName,
            String requestedBinaryName
    ) {
        String packageName = unit.getPackage() == null
                ? ""
                : unit.getPackage().getName().getFullyQualifiedName();
        List<TypeScope> types = new ArrayList<>();
        for (Object declaration : unit.types()) {
            if (declaration instanceof AbstractTypeDeclaration type) {
                collectTypes(type, packageName, null, types);
            }
        }
        for (TypeScope candidate : types) {
            if (candidate.binaryName().equals(requestedBinaryName)) {
                return candidate.declaration();
            }
        }
        if (decompiledBinaryName.equals(requestedBinaryName) && types.size() == 1) {
            return types.getFirst().declaration();
        }
        throw new IllegalArgumentException(
                "No Java type declaration for runtime class " + requestedBinaryName
        );
    }

    private static void collectTypes(
            AbstractTypeDeclaration type,
            String packageName,
            String parentBinaryName,
            List<TypeScope> result
    ) {
        String binaryName = parentBinaryName == null
                ? (packageName.isEmpty() ? "" : packageName + '.') + type.getName().getIdentifier()
                : parentBinaryName + '$' + type.getName().getIdentifier();
        result.add(new TypeScope(binaryName, type));
        for (Object declaration : type.bodyDeclarations()) {
            if (declaration instanceof AbstractTypeDeclaration nested) {
                collectTypes(nested, packageName, binaryName, result);
            }
        }
    }

    private static ASTNode findField(AbstractTypeDeclaration type, SourceTarget target) {
        for (Object declaration : type.bodyDeclarations()) {
            if (!(declaration instanceof FieldDeclaration field)) {
                continue;
            }
            for (Object candidate : field.fragments()) {
                VariableDeclarationFragment fragment = (VariableDeclarationFragment) candidate;
                if (matchesField(fragment.resolveBinding(), target)) {
                    return field;
                }
            }
        }
        if (type instanceof EnumDeclaration enumDeclaration) {
            for (Object candidate : enumDeclaration.enumConstants()) {
                EnumConstantDeclaration constant = (EnumConstantDeclaration) candidate;
                if (matchesField(constant.resolveVariable(), target)) {
                    return constant;
                }
            }
        }
        throw missingMember(target);
    }

    private static boolean matchesField(org.eclipse.jdt.core.dom.IBinding binding, SourceTarget target) {
        CodeSymbol symbol = JavaSymbolResolver.trySymbolForBinding(binding);
        return symbol instanceof CodeSymbol.FieldSymbol exact
                && exact.ownerClassName().equals(target.binaryName())
                && exact.name().equals(target.name())
                && exact.descriptor().equals(target.descriptor());
    }

    private static MethodDeclaration findMethod(AbstractTypeDeclaration type, SourceTarget target) {
        for (Object declaration : type.bodyDeclarations()) {
            if (!(declaration instanceof MethodDeclaration method)) {
                continue;
            }
            CodeSymbol symbol = JavaSymbolResolver.trySymbolForBinding(method.resolveBinding());
            if (symbol instanceof CodeSymbol.MethodSymbol exact
                    && exact.ownerClassName().equals(target.binaryName())
                    && exact.name().equals(target.name())
                    && exact.descriptor().equals(target.descriptor())) {
                return method;
            }
        }
        throw missingMember(target);
    }

    private static SingleVariableDeclaration findRecordComponent(
            AbstractTypeDeclaration type,
            SourceTarget target
    ) {
        if (type instanceof RecordDeclaration record) {
            for (Object candidate : record.recordComponents()) {
                SingleVariableDeclaration component = (SingleVariableDeclaration) candidate;
                if (component.getName().getIdentifier().equals(target.name())
                        && target.descriptor().equals(descriptor(component.getType().resolveBinding()))) {
                    return component;
                }
            }
        }
        throw missingMember(target);
    }

    private static String descriptor(ITypeBinding originalType) {
        if (originalType == null) {
            return null;
        }
        ITypeBinding type = originalType.getErasure();
        if (type.isArray()) {
            return "[".repeat(type.getDimensions()) + descriptor(type.getElementType());
        }
        if (type.isPrimitive()) {
            return switch (type.getName()) {
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "double" -> "D";
                case "float" -> "F";
                case "int" -> "I";
                case "long" -> "J";
                case "short" -> "S";
                case "void" -> "V";
                default -> null;
            };
        }
        String binaryName = type.getBinaryName();
        return binaryName == null ? null : 'L' + binaryName.replace('.', '/') + ';';
    }

    private static IllegalArgumentException missingMember(SourceTarget target) {
        return new IllegalArgumentException(
                "Source member not found: " + target.binaryName() + '.' + target.name() + target.descriptor()
        );
    }

    private static List<String> imports(CompilationUnit unit) {
        List<String> imports = new ArrayList<>(unit.imports().size());
        for (Object candidate : unit.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) candidate;
            StringBuilder value = new StringBuilder();
            if (declaration.isStatic()) {
                value.append("static ");
            }
            value.append(declaration.getName().getFullyQualifiedName());
            if (declaration.isOnDemand()) {
                value.append(".*");
            }
            imports.add(value.toString());
        }
        return List.copyOf(imports);
    }

    private record TypeScope(String binaryName, AbstractTypeDeclaration declaration) {
    }

    private record SourceTarget(String kind, String binaryName, String name, String descriptor) {
        private static SourceTarget parse(Map<String, Object> value) {
            Objects.requireNonNull(value, "target");
            String kind = requireText(value.get("kind"), "target.kind");
            return switch (kind) {
                case "class" -> new SourceTarget(
                        kind,
                        requireBinaryName(value.get("binary_name"), "target.binary_name"),
                        null,
                        null
                );
                case "field", "record_component" -> new SourceTarget(
                        kind,
                        requireBinaryName(value.get("owner"), "target.owner"),
                        requireText(value.get("name"), "target.name"),
                        requireFieldDescriptor(value.get("descriptor"))
                );
                case "method" -> new SourceTarget(
                        kind,
                        requireBinaryName(value.get("owner"), "target.owner"),
                        requireText(value.get("name"), "target.name"),
                        requireMethodDescriptor(value.get("descriptor"))
                );
                default -> throw new IllegalArgumentException(
                        "target.kind must be class, field, method, or record_component"
                );
            };
        }

        private Map<String, Object> asMap() {
            if (this.kind.equals("class")) {
                return Map.of("kind", this.kind, "binary_name", this.binaryName);
            }
            return Map.of(
                    "kind", this.kind,
                    "owner", this.binaryName,
                    "name", this.name,
                    "descriptor", this.descriptor
            );
        }

        private static String requireText(Object value, String name) {
            if (!(value instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(name + " must be a non-blank string");
            }
            return text;
        }

        private static String requireBinaryName(Object value, String name) {
            String binaryName = requireText(value, name);
            if (binaryName.indexOf('/') >= 0 || binaryName.indexOf('\\') >= 0
                    || binaryName.endsWith(".class")) {
                throw new IllegalArgumentException(name + " must be a Java binary name");
            }
            return binaryName;
        }

        private static String requireFieldDescriptor(Object value) {
            String descriptor = requireText(value, "target.descriptor");
            try {
                Type type = Type.getType(descriptor);
                if (type.getSort() == Type.METHOD || type.getSort() == Type.VOID
                        || !type.getDescriptor().equals(descriptor)) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "target.descriptor must be an exact JVM field descriptor: " + descriptor,
                        exception
                );
            }
            return descriptor;
        }

        private static String requireMethodDescriptor(Object value) {
            String descriptor = requireText(value, "target.descriptor");
            try {
                if (!descriptor.startsWith("(") || !Type.getMethodDescriptor(
                        Type.getReturnType(descriptor),
                        Type.getArgumentTypes(descriptor)
                ).equals(descriptor)) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "target.descriptor must be an exact JVM method descriptor: " + descriptor,
                        exception
                );
            }
            return descriptor;
        }
    }

    @FunctionalInterface
    interface RuntimeClassAccess {
        DecompiledSource source(String binaryName);
    }
}
