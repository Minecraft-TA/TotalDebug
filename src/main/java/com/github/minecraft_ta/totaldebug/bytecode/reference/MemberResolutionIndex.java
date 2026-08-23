package com.github.minecraft_ta.totaldebug.bytecode.reference;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

final class MemberResolutionIndex {
    private final Map<String, ClassInfo> classes;
    private final String targetOwner;
    private final boolean methodQuery;
    private final Map<String, Boolean> ownerMatches = new ConcurrentHashMap<>();

    private MemberResolutionIndex(
            Map<String, ClassInfo> classes,
            String targetOwner,
            boolean methodQuery
    ) {
        this.classes = Map.copyOf(classes);
        this.targetOwner = targetOwner;
        this.methodQuery = methodQuery;
    }

    static BuildResult build(
            ReferenceQuery query,
            PreparedClassFileSources sources,
            BooleanSupplier cancellationRequested,
            IntConsumer progress
    ) throws IOException {
        if (!(query instanceof ReferenceQuery.FieldReference)
                && !(query instanceof ReferenceQuery.MethodReference)) {
            throw new IllegalArgumentException("Member resolution requires a field or method query");
        }

        Map<String, ClassInfo> classes = new HashMap<>();
        int[] processed = {0};
        sources.read((origin, bytes) -> {
            if (cancellationRequested.getAsBoolean()) {
                return false;
            }
            try {
                ClassInfoVisitor visitor = new ClassInfoVisitor(query);
                new ClassReader(bytes).accept(
                        visitor,
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
                );
                classes.putIfAbsent(visitor.info().name(), visitor.info());
            } catch (RuntimeException exception) {
                throw new IOException("Unable to index member owner from class file " + origin, exception);
            }
            progress.accept(++processed[0]);
            return !cancellationRequested.getAsBoolean();
        });

        String targetOwner = switch (query) {
            case ReferenceQuery.FieldReference field -> internalName(field.ownerClassName());
            case ReferenceQuery.MethodReference method -> internalName(method.ownerClassName());
            case ReferenceQuery.ClassReference ignored -> throw new IllegalArgumentException(
                    "Member resolution requires a field or method query"
            );
        };
        return new BuildResult(
                new MemberResolutionIndex(classes, targetOwner, query instanceof ReferenceQuery.MethodReference),
                processed[0],
                cancellationRequested.getAsBoolean()
        );
    }

    boolean matchesOwner(String symbolicOwner) {
        if (this.targetOwner.equals(symbolicOwner)) {
            return true;
        }
        return this.ownerMatches.computeIfAbsent(
                symbolicOwner,
                this.methodQuery ? this::methodResolvesToTarget : this::fieldResolvesToTarget
        );
    }

    private boolean fieldResolvesToTarget(String symbolicOwner) {
        return resolveField(symbolicOwner, new HashSet<>()) == Resolution.TARGET;
    }

    private Resolution resolveField(String owner, Set<String> visiting) {
        if (this.targetOwner.equals(owner)) {
            return Resolution.TARGET;
        }
        if (!visiting.add(owner)) {
            return Resolution.NOT_FOUND;
        }
        ClassInfo info = this.classes.get(owner);
        if (info == null) {
            return Resolution.NOT_FOUND;
        }
        if (info.declaresTarget()) {
            return Resolution.OTHER;
        }
        for (String interfaceName : info.interfaces()) {
            Resolution interfaceResolution = resolveField(interfaceName, visiting);
            if (interfaceResolution != Resolution.NOT_FOUND) {
                return interfaceResolution;
            }
        }
        return info.superName() == null
                ? Resolution.NOT_FOUND
                : resolveField(info.superName(), visiting);
    }

    private boolean methodResolvesToTarget(String symbolicOwner) {
        Set<String> visitedClasses = new HashSet<>();
        String current = symbolicOwner;
        while (current != null && visitedClasses.add(current)) {
            if (this.targetOwner.equals(current)) {
                return true;
            }
            ClassInfo info = this.classes.get(current);
            if (info == null) {
                break;
            }
            if (info.declaresTarget()) {
                return false;
            }
            current = info.superName();
        }

        current = symbolicOwner;
        visitedClasses.clear();
        Set<String> visitedInterfaces = new HashSet<>();
        while (current != null && visitedClasses.add(current)) {
            ClassInfo info = this.classes.get(current);
            if (info == null) {
                break;
            }
            for (String interfaceName : info.interfaces()) {
                if (interfaceResolvesToTarget(interfaceName, visitedInterfaces)) {
                    return true;
                }
            }
            current = info.superName();
        }
        return false;
    }

    private boolean interfaceResolvesToTarget(String interfaceName, Set<String> visited) {
        if (this.targetOwner.equals(interfaceName)) {
            return true;
        }
        if (!visited.add(interfaceName)) {
            return false;
        }
        ClassInfo info = this.classes.get(interfaceName);
        if (info == null || info.declaresTarget()) {
            return false;
        }
        for (String parent : info.interfaces()) {
            if (interfaceResolvesToTarget(parent, visited)) {
                return true;
            }
        }
        return false;
    }

    record BuildResult(MemberResolutionIndex index, int processedClassFiles, boolean cancelled) {
    }

    private enum Resolution {
        TARGET,
        OTHER,
        NOT_FOUND
    }

    private record ClassInfo(String name, String superName, List<String> interfaces, boolean declaresTarget) {
    }

    private static final class ClassInfoVisitor extends ClassVisitor {
        private final ReferenceQuery query;
        private String name;
        private String superName;
        private List<String> interfaces = List.of();
        private boolean declaresTarget;

        private ClassInfoVisitor(ReferenceQuery query) {
            super(Opcodes.ASM9);
            this.query = query;
        }

        @Override
        public void visit(
                int version,
                int access,
                String name,
                String signature,
                String superName,
                String[] interfaces
        ) {
            this.name = name;
            this.superName = superName;
            this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (this.query instanceof ReferenceQuery.FieldReference field
                    && field.name().equals(name)
                    && field.descriptor().equals(descriptor)) {
                this.declaresTarget = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions
        ) {
            if (this.query instanceof ReferenceQuery.MethodReference method
                    && method.name().equals(name)
                    && method.descriptor().equals(descriptor)) {
                this.declaresTarget = true;
            }
            return null;
        }

        private ClassInfo info() {
            return new ClassInfo(this.name, this.superName, this.interfaces, this.declaresTarget);
        }
    }

    private static String internalName(String binaryName) {
        return binaryName.replace('.', '/');
    }
}
