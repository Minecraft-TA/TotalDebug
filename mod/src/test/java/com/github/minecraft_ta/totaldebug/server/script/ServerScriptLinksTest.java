package com.github.minecraft_ta.totaldebug.server.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerScriptLinksTest {
    private static final ClassLoader LOADER = ServerScriptLinksTest.class.getClassLoader();

    @Test
    void aScriptThatResolvesRuns() {
        assertEquals("", ServerScriptLinks.refusal(script(method -> {
            method.visitLdcInsn("x");
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
            method.visitInsn(Opcodes.POP);
        }), LOADER));
    }

    @Test
    void aReferenceTheServerDoesNotHaveIsNamed() {
        assertEquals("Not on the server:\n  com.example.Missing", ServerScriptLinks.refusal(script(method -> {
            method.visitTypeInsn(Opcodes.NEW, "com/example/Missing");
            method.visitInsn(Opcodes.POP);
        }), LOADER));
    }

    @Test
    void aClientClassIsRefusedEvenWhereItWouldResolve() {
        assertEquals("Client classes are not available to server scripts:\n  net.minecraft.client.Minecraft",
                ServerScriptLinks.refusal(script(method -> {
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft", "getInstance",
                            "()Lnet/minecraft/client/Minecraft;", false);
                    method.visitInsn(Opcodes.POP);
                }), LOADER));
    }

    @Test
    void bytecodeThatCannotBeReadIsRefused() {
        String refusal = ServerScriptLinks.refusal(new ScriptBytecode("Probe", Map.of("Probe", new byte[]{1, 2, 3})), LOADER);

        assertEquals("The script's bytecode could not be read: ", refusal.substring(0, "The script's bytecode could not be read: ".length()));
    }

    private static ScriptBytecode script(Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "Probe", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        body.accept(method);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return new ScriptBytecode("Probe", Map.of("Probe", writer.toByteArray()));
    }
}
