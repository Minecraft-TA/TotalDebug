package com.github.minecraft_ta.totalDebugCompanion.bytecode.reference;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;

final class ReferenceFixtures {
    static final String TARGET = "fixture/Target";
    static final String REFERENCES = "fixture/References";

    private ReferenceFixtures() {
    }

    static List<ReferenceLocation> classReferenceLocations() {
        return List.of(
                ReferenceLocation.classDeclaration(REFERENCES.replace('/', '.')),
                ReferenceLocation.field(REFERENCES.replace('/', '.'), "targetField", "Lfixture/Target;"),
                location("annotationUse", "()V"),
                location("classLiteral", "()V"),
                location("descriptorUse", "(Lfixture/Target;)Lfixture/Target;"),
                location("exceptionUse", "()V"),
                location("fieldInt", "()V"),
                location("fieldString", "()V"),
                location("genericUse", "(Ljava/util/List;)V"),
                location("methodHandle", "()V"),
                location("methodInt", "()V"),
                location("methodNoArgs", "()V"),
                location("typeInstructions", "()V"),
                ReferenceLocation.recordComponent(
                        REFERENCES.replace('/', '.'),
                        "targetComponent",
                        "Lfixture/Target;"
                )
        );
    }

    static ReferenceLocation location(String methodName, String descriptor) {
        return ReferenceLocation.method(REFERENCES.replace('/', '.'), methodName, descriptor);
    }

    static byte[] referenceFixture(String internalName) {
        ClassWriter writer = beginClass(internalName, new String[]{TARGET});
        writer.visitField(Opcodes.ACC_PRIVATE, "targetField", "Lfixture/Target;", null, null).visitEnd();
        writer.visitRecordComponent("targetComponent", "Lfixture/Target;", null).visitEnd();
        emptyMethod(writer, "descriptorUse", "(Lfixture/Target;)Lfixture/Target;", null, Opcodes.ARETURN);
        emptyMethod(
                writer,
                "genericUse",
                "(Ljava/util/List;)V",
                "(Ljava/util/List<Lfixture/Target;>;)V",
                Opcodes.RETURN
        );
        var annotationUse = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "annotationUse",
                "()V",
                null,
                null
        );
        annotationUse.visitAnnotation("Lfixture/Target;", true).visitEnd();
        annotationUse.visitCode();
        annotationUse.visitInsn(Opcodes.RETURN);
        annotationUse.visitMaxs(0, 0);
        annotationUse.visitEnd();
        emptyMethodWithExceptions(writer, "exceptionUse", new String[]{TARGET});

        var methodNoArgs = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "methodNoArgs", "()V", null, null);
        methodNoArgs.visitCode();
        methodNoArgs.visitInsn(Opcodes.ACONST_NULL);
        methodNoArgs.visitMethodInsn(Opcodes.INVOKEINTERFACE, TARGET, "run", "()V", true);
        methodNoArgs.visitInsn(Opcodes.ACONST_NULL);
        methodNoArgs.visitMethodInsn(Opcodes.INVOKEINTERFACE, TARGET, "run", "()V", true);
        methodNoArgs.visitInsn(Opcodes.RETURN);
        methodNoArgs.visitMaxs(1, 0);
        methodNoArgs.visitEnd();

        var methodInt = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "methodInt", "()V", null, null);
        methodInt.visitCode();
        methodInt.visitInsn(Opcodes.ACONST_NULL);
        methodInt.visitInsn(Opcodes.ICONST_0);
        methodInt.visitMethodInsn(Opcodes.INVOKEINTERFACE, TARGET, "run", "(I)V", true);
        methodInt.visitInsn(Opcodes.RETURN);
        methodInt.visitMaxs(2, 0);
        methodInt.visitEnd();

        fieldMethod(writer, "fieldInt", "I", Opcodes.POP);
        fieldMethod(writer, "fieldString", "Ljava/lang/String;", Opcodes.POP);

        var classLiteral = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "classLiteral", "()V", null, null);
        classLiteral.visitCode();
        classLiteral.visitLdcInsn(Type.getObjectType(TARGET));
        classLiteral.visitInsn(Opcodes.POP);
        classLiteral.visitInsn(Opcodes.RETURN);
        classLiteral.visitMaxs(1, 0);
        classLiteral.visitEnd();

        var typeInstructions = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "typeInstructions",
                "()V",
                null,
                null
        );
        typeInstructions.visitCode();
        typeInstructions.visitInsn(Opcodes.ACONST_NULL);
        typeInstructions.visitTypeInsn(Opcodes.CHECKCAST, TARGET);
        typeInstructions.visitInsn(Opcodes.POP);
        typeInstructions.visitInsn(Opcodes.ICONST_1);
        typeInstructions.visitTypeInsn(Opcodes.ANEWARRAY, TARGET);
        typeInstructions.visitInsn(Opcodes.POP);
        typeInstructions.visitInsn(Opcodes.ICONST_1);
        typeInstructions.visitInsn(Opcodes.ICONST_1);
        typeInstructions.visitMultiANewArrayInsn("[[Lfixture/Target;", 2);
        typeInstructions.visitInsn(Opcodes.POP);
        typeInstructions.visitInsn(Opcodes.RETURN);
        typeInstructions.visitMaxs(2, 0);
        typeInstructions.visitEnd();

        Handle bootstrap = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;",
                false
        );
        var methodHandle = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "methodHandle", "()V", null, null);
        methodHandle.visitCode();
        methodHandle.visitInsn(Opcodes.ACONST_NULL);
        methodHandle.visitInvokeDynamicInsn(
                "run",
                "(Lfixture/Target;)Ljava/lang/Runnable;",
                bootstrap,
                Type.getMethodType("()V"),
                new Handle(Opcodes.H_INVOKEINTERFACE, TARGET, "run", "()V", true),
                Type.getMethodType("()V")
        );
        methodHandle.visitInsn(Opcodes.POP);
        methodHandle.visitInsn(Opcodes.RETURN);
        methodHandle.visitMaxs(1, 0);
        methodHandle.visitEnd();

        var unrelated = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "unrelated", "()V", null, null);
        unrelated.visitCode();
        unrelated.visitInsn(Opcodes.ACONST_NULL);
        unrelated.visitMethodInsn(Opcodes.INVOKEINTERFACE, "fixture/TargetExtra", "run", "()V", true);
        unrelated.visitInsn(Opcodes.RETURN);
        unrelated.visitMaxs(1, 0);
        unrelated.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] targetDeclarationFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                TARGET,
                null,
                "java/lang/Object",
                null
        );
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "VALUE", "I", null, null).visitEnd();
        writer.visitField(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "VALUE",
                "Ljava/lang/String;",
                null,
                null
        ).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "run", "()V", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "run", "(I)V", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] memberOwnerFixture(String internalName, String superName, boolean declaresMembers) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, superName, null);
        if (declaresMembers) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "VALUE", "I", null, null).visitEnd();
            emptyMethod(writer, "run", "()V", null, Opcodes.RETURN);
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] hierarchyReferencesFixture() {
        ClassWriter writer = beginClass("fixture/HierarchyReferences", null);
        memberCallMethod(writer, "inheritedMethod", "fixture/Sub");
        memberCallMethod(writer, "overriddenMethod", "fixture/Override");
        memberFieldMethod(writer, "inheritedField", "fixture/Sub");
        memberFieldMethod(writer, "overriddenField", "fixture/Override");
        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] interfaceFixture(String internalName, String parent, boolean declaresMethod) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                internalName,
                null,
                "java/lang/Object",
                parent == null ? null : new String[]{parent}
        );
        if (declaresMethod) {
            writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "run", "()V", null, null).visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] implementationFixture(String internalName, String interfaceName) {
        ClassWriter writer = beginClass(internalName, new String[]{interfaceName});
        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] interfaceReferencesFixture() {
        ClassWriter writer = beginClass("fixture/InterfaceReferences", null);
        memberCallMethod(writer, "inheritedInterface", "fixture/InterfaceImpl");
        memberCallMethod(writer, "overriddenInterface", "fixture/OverrideImpl");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter beginClass(String internalName, String[] interfaces) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", interfaces);
        return writer;
    }

    private static void emptyMethod(
            ClassWriter writer,
            String name,
            String descriptor,
            String signature,
            int returnOpcode
    ) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, signature, null);
        method.visitCode();
        if (returnOpcode == Opcodes.ARETURN) {
            method.visitInsn(Opcodes.ACONST_NULL);
        }
        method.visitInsn(returnOpcode);
        method.visitMaxs(returnOpcode == Opcodes.ARETURN ? 1 : 0, Type.getArgumentTypes(descriptor).length);
        method.visitEnd();
    }

    private static void fieldMethod(ClassWriter writer, String methodName, String descriptor, int popOpcode) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, "()V", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "VALUE", descriptor);
        method.visitInsn(popOpcode);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
    }

    private static void memberCallMethod(ClassWriter writer, String methodName, String owner) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "run", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
    }

    private static void memberFieldMethod(ClassWriter writer, String methodName, String owner) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, "()V", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, owner, "VALUE", "I");
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
    }

    private static void emptyMethodWithExceptions(ClassWriter writer, String name, String[] exceptions) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, exceptions);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }
}
