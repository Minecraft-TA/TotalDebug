package com.github.minecraft_ta.totaldebug.script;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.tools.ForwardingJavaFileObject;
import javax.tools.JavaFileObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Compiler-only class view that makes application members visible for snippet attribution. */
final class RelaxedClassInput extends ForwardingJavaFileObject<JavaFileObject> {
    private byte[] relaxedBytes;

    RelaxedClassInput(JavaFileObject delegate) {
        super(delegate);
    }

    JavaFileObject delegate() {
        return this.fileObject;
    }

    @Override
    public InputStream openInputStream() throws IOException {
        if (this.relaxedBytes == null) {
            try (InputStream input = this.fileObject.openInputStream()) {
                this.relaxedBytes = relax(input.readAllBytes());
            }
        }
        return new ByteArrayInputStream(this.relaxedBytes);
    }

    private static byte[] relax(byte[] classFile) {
        ClassReader reader = new ClassReader(classFile);
        if (reader.getClassName().equals("com/github/minecraft_ta/totaldebug/script/ScriptProgram")) {
            return classFile;
        }
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visit(
                    int version,
                    int access,
                    String name,
                    String signature,
                    String superName,
                    String[] interfaces
            ) {
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                super.visitInnerClass(name, outerName, innerName, access);
            }

            @Override
            public FieldVisitor visitField(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    Object value
            ) {
                return super.visitField(visible(access), name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                return super.visitMethod(visible(access), name, descriptor, signature, exceptions);
            }
        }, 0);
        return writer.toByteArray();
    }

    private static int visible(int access) {
        return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
    }
}
