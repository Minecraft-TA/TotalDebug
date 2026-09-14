package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.decompile.CompanionDecompilationService;
import com.github.minecraft_ta.totalDebugCompanion.decompile.DecompiledSource;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import org.objectweb.asm.Type;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Returns exact source scopes from runtime classes. */
final class CompanionMcpRuntimeSource {
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

        var document = decompiled.document();
        var location = switch (target.kind()) {
            case "class" -> ReferenceLocation.classDeclaration(target.binaryName());
            case "field" -> ReferenceLocation.field(target.binaryName(), target.name(), target.descriptor());
            case "method" -> ReferenceLocation.method(target.binaryName(), target.name(), target.descriptor());
            case "record_component" -> ReferenceLocation.recordComponent(target.binaryName(), target.name(), target.descriptor());
            default -> throw new IllegalStateException("Unsupported source target: " + target.kind());
        };
        var scope = document.declaration(location).orElseThrow(() -> new IllegalArgumentException(
                "Source declaration not found: " + location));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", target.asMap());
        if (!document.packageName().isEmpty()) result.put("package", document.packageName());
        result.put("imports", document.imports());
        result.put("start_line", document.lineAt(scope.start()));
        result.put("source", document.contents().substring(scope.start(), scope.start() + scope.length()));
        return result;
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
