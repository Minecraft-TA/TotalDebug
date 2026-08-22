package com.github.minecraft_ta.totaldebug.decompiler;

import com.github.minecraft_ta.totaldebug.decompiler.naming.GeneratedVariableNames;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class SyntheticLambdaDebugMetadata {
    private static final String MINECRAFT_PACKAGE = "net/minecraft/";

    private SyntheticLambdaDebugMetadata() {
    }

    static byte[] removeGeneratedLocalNames(String internalName, byte[] classBytes) {
        if (!internalName.startsWith(MINECRAFT_PACKAGE)) {
            return classBytes;
        }

        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        boolean[] changed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ((access & Opcodes.ACC_SYNTHETIC) == 0 || !name.startsWith("lambda$")) {
                    return visitor;
                }

                return new MethodVisitor(Opcodes.ASM9, visitor) {
                    @Override
                    public void visitLocalVariable(
                            String name,
                            String descriptor,
                            String signature,
                            org.objectweb.asm.Label start,
                            org.objectweb.asm.Label end,
                            int index
                    ) {
                        if (GeneratedVariableNames.matches(name)) {
                            changed[0] = true;
                            return;
                        }
                        super.visitLocalVariable(name, descriptor, signature, start, end, index);
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : classBytes;
    }
}
