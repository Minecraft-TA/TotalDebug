package com.github.minecraft_ta.totaldebug.bytecode.reference;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.util.LinkedHashSet;
import java.util.Set;

final class BytecodeReferenceScanner {
    private BytecodeReferenceScanner() {
    }

    static Set<ReferenceLocation> scan(
            byte[] classBytes,
            ReferenceQuery query,
            MemberResolutionIndex memberResolution
    ) {
        Set<ReferenceLocation> locations = new LinkedHashSet<>();
        QueryMatcher matcher = new QueryMatcher(query, memberResolution);
        new ClassReader(classBytes).accept(
                new ReferenceClassVisitor(matcher, locations),
                ClassReader.SKIP_FRAMES
        );
        return locations;
    }

    private static final class ReferenceClassVisitor extends ClassVisitor {
        private final QueryMatcher matcher;
        private final Set<ReferenceLocation> locations;
        private String className;
        private ReferenceCollector classReferences;

        private ReferenceClassVisitor(QueryMatcher matcher, Set<ReferenceLocation> locations) {
            super(Opcodes.ASM9);
            this.matcher = matcher;
            this.locations = locations;
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
            this.className = name.replace('/', '.');
            this.classReferences = collector(ReferenceLocation.classDeclaration(this.className));
            this.classReferences.checkSignature(signature, false);
            this.classReferences.checkInternalName(superName);
            if (interfaces != null) {
                for (String interfaceName : interfaces) {
                    this.classReferences.checkInternalName(interfaceName);
                }
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return this.classReferences.annotation(descriptor);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(
                int typeReference,
                TypePath typePath,
                String descriptor,
                boolean visible
        ) {
            return this.classReferences.annotation(descriptor);
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            this.classReferences.checkInternalName(permittedSubclass);
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
            ReferenceCollector references = collector(
                    ReferenceLocation.recordComponent(this.className, name, descriptor)
            );
            references.checkDescriptor(descriptor);
            references.checkSignature(signature, true);
            return new RecordComponentVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    return references.annotation(annotationDescriptor);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(
                        int typeReference,
                        TypePath typePath,
                        String annotationDescriptor,
                        boolean visible
                ) {
                    return references.annotation(annotationDescriptor);
                }
            };
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            ReferenceCollector references = collector(ReferenceLocation.field(this.className, name, descriptor));
            references.checkDescriptor(descriptor);
            references.checkSignature(signature, true);
            references.checkConstant(value);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    return references.annotation(annotationDescriptor);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(
                        int typeReference,
                        TypePath typePath,
                        String annotationDescriptor,
                        boolean visible
                ) {
                    return references.annotation(annotationDescriptor);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions
        ) {
            ReferenceCollector references = collector(ReferenceLocation.method(this.className, name, descriptor));
            references.checkDescriptor(descriptor);
            references.checkSignature(signature, false);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    references.checkInternalName(exception);
                }
            }
            return new ReferenceMethodVisitor(this.matcher, references);
        }

        private ReferenceCollector collector(ReferenceLocation location) {
            return new ReferenceCollector(this.matcher, this.locations, location);
        }
    }

    private static final class ReferenceMethodVisitor extends MethodVisitor {
        private final QueryMatcher matcher;
        private final ReferenceCollector references;

        private ReferenceMethodVisitor(QueryMatcher matcher, ReferenceCollector references) {
            super(Opcodes.ASM9);
            this.matcher = matcher;
            this.references = references;
        }

        @Override
        public AnnotationVisitor visitAnnotationDefault() {
            return this.references.annotationValues();
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return this.references.annotation(descriptor);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(
                int typeReference,
                TypePath typePath,
                String descriptor,
                boolean visible
        ) {
            return this.references.annotation(descriptor);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
            return this.references.annotation(descriptor);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            this.references.markIf(this.matcher.matchesTypeInstruction(type));
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            this.references.markIf(this.matcher.matchesField(owner, name, descriptor));
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            this.references.markIf(this.matcher.matchesMethod(owner, name, descriptor));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethod, Object... arguments) {
            this.references.checkDescriptor(descriptor);
            this.references.checkConstant(bootstrapMethod);
            for (Object argument : arguments) {
                this.references.checkConstant(argument);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            this.references.checkConstant(value);
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(
                int typeReference,
                TypePath typePath,
                String descriptor,
                boolean visible
        ) {
            return this.references.annotation(descriptor);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            this.references.checkInternalName(type);
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(
                int typeReference,
                TypePath typePath,
                String descriptor,
                boolean visible
        ) {
            return this.references.annotation(descriptor);
        }

        @Override
        public void visitLocalVariable(
                String name,
                String descriptor,
                String signature,
                Label start,
                Label end,
                int index
        ) {
            this.references.checkDescriptor(descriptor);
            this.references.checkSignature(signature, true);
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(
                int typeReference,
                TypePath typePath,
                Label[] start,
                Label[] end,
                int[] index,
                String descriptor,
                boolean visible
        ) {
            return this.references.annotation(descriptor);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            this.references.checkDescriptor(descriptor);
        }
    }

    private static final class ReferenceCollector {
        private final QueryMatcher matcher;
        private final Set<ReferenceLocation> locations;
        private final ReferenceLocation location;

        private ReferenceCollector(
                QueryMatcher matcher,
                Set<ReferenceLocation> locations,
                ReferenceLocation location
        ) {
            this.matcher = matcher;
            this.locations = locations;
            this.location = location;
        }

        private AnnotationVisitor annotation(String descriptor) {
            checkDescriptor(descriptor);
            return annotationValues();
        }

        private AnnotationVisitor annotationValues() {
            return new AnnotationReferenceVisitor(this);
        }

        private void checkInternalName(String internalName) {
            if (internalName != null) {
                markIf(this.matcher.matchesInternalName(internalName));
            }
        }

        private void checkDescriptor(String descriptor) {
            if (descriptor != null) {
                markIf(this.matcher.matchesDescriptor(descriptor));
            }
        }

        private void checkSignature(String signature, boolean typeSignature) {
            if (signature != null) {
                markIf(this.matcher.matchesSignature(signature, typeSignature));
            }
        }

        private void checkConstant(Object value) {
            if (value != null) {
                markIf(this.matcher.matchesConstant(value));
            }
        }

        private void markIf(boolean matches) {
            if (matches) {
                this.locations.add(this.location);
            }
        }
    }

    private static final class AnnotationReferenceVisitor extends AnnotationVisitor {
        private final ReferenceCollector references;

        private AnnotationReferenceVisitor(ReferenceCollector references) {
            super(Opcodes.ASM9);
            this.references = references;
        }

        @Override
        public void visit(String name, Object value) {
            this.references.checkConstant(value);
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            this.references.checkDescriptor(descriptor);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            return this.references.annotation(descriptor);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            return this;
        }
    }

    private static final class QueryMatcher {
        private final ReferenceQuery query;
        private final String targetInternalName;
        private final MemberResolutionIndex memberResolution;

        private QueryMatcher(ReferenceQuery query, MemberResolutionIndex memberResolution) {
            this.query = query;
            this.memberResolution = memberResolution;
            if (!(query instanceof ReferenceQuery.ClassReference) && memberResolution == null) {
                throw new IllegalArgumentException("Member queries require an owner-resolution index");
            }
            this.targetInternalName = switch (query) {
                case ReferenceQuery.ClassReference classReference -> internalName(classReference.className());
                case ReferenceQuery.FieldReference fieldReference -> internalName(fieldReference.ownerClassName());
                case ReferenceQuery.MethodReference methodReference -> internalName(methodReference.ownerClassName());
            };
        }

        private boolean matchesInternalName(String internalName) {
            return this.query instanceof ReferenceQuery.ClassReference
                    && this.targetInternalName.equals(internalName);
        }

        private boolean matchesTypeInstruction(String type) {
            if (!(this.query instanceof ReferenceQuery.ClassReference)) {
                return false;
            }
            return type.startsWith("[")
                    ? matchesDescriptor(type)
                    : this.targetInternalName.equals(type);
        }

        private boolean matchesDescriptor(String descriptor) {
            if (!(this.query instanceof ReferenceQuery.ClassReference)) {
                return false;
            }
            return typeContainsTarget(Type.getType(descriptor));
        }

        private boolean matchesSignature(String signature, boolean typeSignature) {
            if (!(this.query instanceof ReferenceQuery.ClassReference)) {
                return false;
            }
            SignatureTypeMatcher visitor = new SignatureTypeMatcher(this.targetInternalName);
            SignatureReader reader = new SignatureReader(signature);
            if (typeSignature) {
                reader.acceptType(visitor);
            } else {
                reader.accept(visitor);
            }
            return visitor.matches();
        }

        private boolean matchesField(String owner, String name, String descriptor) {
            if (this.query instanceof ReferenceQuery.ClassReference) {
                return this.targetInternalName.equals(owner) || matchesDescriptor(descriptor);
            }
            if (this.query instanceof ReferenceQuery.FieldReference fieldReference) {
                return this.memberResolution.matchesOwner(owner)
                        && fieldReference.name().equals(name)
                        && fieldReference.descriptor().equals(descriptor);
            }
            return false;
        }

        private boolean matchesMethod(String owner, String name, String descriptor) {
            if (this.query instanceof ReferenceQuery.ClassReference) {
                return this.targetInternalName.equals(owner) || matchesDescriptor(descriptor);
            }
            if (this.query instanceof ReferenceQuery.MethodReference methodReference) {
                return this.memberResolution.matchesOwner(owner)
                        && methodReference.name().equals(name)
                        && methodReference.descriptor().equals(descriptor);
            }
            return false;
        }

        private boolean matchesConstant(Object value) {
            if (value instanceof Type type) {
                return this.query instanceof ReferenceQuery.ClassReference && typeContainsTarget(type);
            }
            if (value instanceof Handle handle) {
                return matchesHandle(handle);
            }
            if (value instanceof ConstantDynamic dynamic) {
                if (matchesDescriptor(dynamic.getDescriptor()) || matchesHandle(dynamic.getBootstrapMethod())) {
                    return true;
                }
                for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                    if (matchesConstant(dynamic.getBootstrapMethodArgument(index))) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean matchesHandle(Handle handle) {
            return switch (handle.getTag()) {
                case Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC ->
                        matchesField(handle.getOwner(), handle.getName(), handle.getDesc());
                default -> matchesMethod(handle.getOwner(), handle.getName(), handle.getDesc());
            };
        }

        private boolean typeContainsTarget(Type type) {
            return switch (type.getSort()) {
                case Type.OBJECT -> this.targetInternalName.equals(type.getInternalName());
                case Type.ARRAY -> typeContainsTarget(type.getElementType());
                case Type.METHOD -> {
                    if (typeContainsTarget(type.getReturnType())) {
                        yield true;
                    }
                    boolean match = false;
                    for (Type argument : type.getArgumentTypes()) {
                        if (typeContainsTarget(argument)) {
                            match = true;
                            break;
                        }
                    }
                    yield match;
                }
                default -> false;
            };
        }

        private static String internalName(String binaryName) {
            return binaryName.replace('.', '/');
        }
    }

    private static final class SignatureTypeMatcher extends SignatureVisitor {
        private final String targetInternalName;
        private String currentClassName;
        private boolean matches;

        private SignatureTypeMatcher(String targetInternalName) {
            super(Opcodes.ASM9);
            this.targetInternalName = targetInternalName;
        }

        @Override
        public void visitClassType(String name) {
            this.currentClassName = name;
            checkCurrentClass();
        }

        @Override
        public void visitInnerClassType(String name) {
            this.currentClassName = this.currentClassName + '$' + name;
            checkCurrentClass();
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            return new NestedSignatureTypeMatcher(this);
        }

        @Override
        public void visitEnd() {
            this.currentClassName = null;
        }

        private void checkCurrentClass() {
            this.matches |= this.targetInternalName.equals(this.currentClassName);
        }

        private boolean matches() {
            return this.matches;
        }
    }

    private static final class NestedSignatureTypeMatcher extends SignatureVisitor {
        private final SignatureTypeMatcher root;
        private String currentClassName;

        private NestedSignatureTypeMatcher(SignatureTypeMatcher root) {
            super(Opcodes.ASM9);
            this.root = root;
        }

        @Override
        public void visitClassType(String name) {
            this.currentClassName = name;
            checkCurrentClass();
        }

        @Override
        public void visitInnerClassType(String name) {
            this.currentClassName = this.currentClassName + '$' + name;
            checkCurrentClass();
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            return new NestedSignatureTypeMatcher(this.root);
        }

        private void checkCurrentClass() {
            this.root.matches |= this.root.targetInternalName.equals(this.currentClassName);
        }
    }
}
