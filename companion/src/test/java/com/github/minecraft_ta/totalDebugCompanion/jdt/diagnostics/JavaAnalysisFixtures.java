package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.tth05.jindex.ClassIndex;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class JavaAnalysisFixtures {
    public static ClassIndex index(Class<?>... extra) throws Exception {
        var bytes = new ArrayList<byte[]>();
        var types = new ArrayList<>(List.of(Object.class, Boolean.class, String.class, Throwable.class, Error.class,
                Exception.class, RuntimeException.class, Integer.class, Number.class, Override.class));
        types.addAll(Arrays.asList(extra));
        for (var type : types) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) { bytes.add(stream.readAllBytes()); }
        }
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, JavaSnippetSource.PROGRAM_TYPE.replace('.', '/'), null, "java/lang/Object", null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(1, 1); constructor.visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "run", "()Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"}).visitEnd();
        var method = writer.visitMethod(Opcodes.ACC_PROTECTED, "noResult", "()Ljava/lang/Object;", null, null);
        method.visitCode(); method.visitInsn(Opcodes.ACONST_NULL); method.visitInsn(Opcodes.ARETURN); method.visitMaxs(1, 1); method.visitEnd();
        writer.visitEnd(); bytes.add(writer.toByteArray());
        return ClassIndex.fromBytes(bytes);
    }
}
