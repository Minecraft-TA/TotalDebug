package com.github.minecraft_ta.totaldebug.evaluation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptReferencesTest {
    private static final Map<String, byte[]> SCRIPT = Map.of(
            "probe.Script", type("probe/Script", method -> {
                method.visitLdcInsn("x");
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
                method.visitInsn(Opcodes.POP);
                method.visitLdcInsn("x");
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "nope", "()V", false);
                method.visitTypeInsn(Opcodes.NEW, "com/example/Missing");
                method.visitInsn(Opcodes.POP);
                method.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                method.visitInsn(Opcodes.POP);
                method.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Integer", "NOPE", "I");
                method.visitInsn(Opcodes.POP);
                method.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                method.visitInsn(Opcodes.DUP);
                method.visitInsn(Opcodes.ICONST_1);
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Z)V", false);
                method.visitInsn(Opcodes.POP);
                method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true);
                method.visitInsn(Opcodes.ACONST_NULL);
                method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "forEach", "(Ljava/util/function/Consumer;)V", true);
                method.visitMethodInsn(Opcodes.INVOKESTATIC, "probe/Helper", "help", "()V", false);
                method.visitInsn(Opcodes.ICONST_1);
                method.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
                method.visitInsn(Opcodes.POP);
            }),
            "probe.Helper", type("probe/Helper", method -> {
            }));

    @Test
    void namesWhatTheLoaderCannotResolveAsJavaWouldName() {
        List<String> unresolved = ScriptReferences.read(SCRIPT).unresolved(ScriptReferencesTest.class.getClassLoader());

        assertEquals(List.of("com.example.Missing", "java.lang.String.nope()", "java.lang.Integer.NOPE",
                "new java.lang.StringBuilder(boolean)"), unresolved,
                "an inherited default method, an array's clone and the script's own classes resolve");
    }

    @Test
    void aMemberTheCompilerRoutedThroughTheLinkerIsTheMemberItNames() {
        Handle linker = new Handle(Opcodes.H_INVOKESTATIC, Type.getInternalName(ScriptAccessLinker.class), "bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;", false);
        Map<String, byte[]> script = Map.of("probe.Linked", type("probe/Linked", method -> {
            method.visitInvokeDynamicInsn("length", "(Ljava/lang/String;)I", linker,
                    "java.lang.String", "length", "()I", ScriptAccessLinker.INVOKE_VIRTUAL);
            method.visitInvokeDynamicInsn("nope", "()I", linker,
                    "java.lang.Integer", "nope", "()I", ScriptAccessLinker.INVOKE_STATIC);
            method.visitInvokeDynamicInsn("hidden", "()I", linker,
                    "java.lang.Integer", "hidden", "I", ScriptAccessLinker.GET_STATIC);
            method.visitInvokeDynamicInsn("initializeClass", "()V", linker,
                    "com.example.Gone", "<clinit>", "()V", ScriptAccessLinker.INITIALIZE_CLASS);
        }));

        assertEquals(List.of("com.example.Gone", "java.lang.Integer.nope()", "java.lang.Integer.hidden"),
                ScriptReferences.read(script).unresolved(ScriptReferencesTest.class.getClassLoader()));
    }

    @Test
    void aMemberUsedAsAnotherKindIsNamedWithTheKindTheServerHas() {
        Handle linker = new Handle(Opcodes.H_INVOKESTATIC, Type.getInternalName(ScriptAccessLinker.class), "bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;", false);
        Map<String, byte[]> script = Map.of("probe.Kinds", type("probe/Kinds", method -> {
            method.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Integer", "value", "I");
            method.visitInsn(Opcodes.POP);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "length", "()I", false);
            method.visitInsn(Opcodes.POP);
            method.visitInvokeDynamicInsn("valueOf", "(Ljava/lang/Integer;I)Ljava/lang/Integer;", linker,
                    "java.lang.Integer", "valueOf", "(I)Ljava/lang/Integer;", ScriptAccessLinker.INVOKE_VIRTUAL);
            method.visitInvokeDynamicInsn("newInstance", "()Ljava/lang/Number;", linker,
                    "java.lang.Number", "<init>", "()V", ScriptAccessLinker.NEW_INSTANCE);
        }));

        assertEquals(List.of("java.lang.Integer.value (not static on the server)",
                        "java.lang.String.length() (not static on the server)",
                        "java.lang.Integer.valueOf(int) (static on the server)",
                        "new java.lang.Number() (abstract on the server)"),
                ScriptReferences.read(script).unresolved(ScriptReferencesTest.class.getClassLoader()));
    }

    @Test
    void aScriptClassTheServerWillNotDefineIsNamedWithTheReason() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "probe/Sub", null, "java/lang/String", null);
        writer.visitEnd();

        List<String> unresolved = ScriptReferences.read(Map.of("probe.Sub", writer.toByteArray()))
                .unresolved(ScriptReferencesTest.class.getClassLoader());

        assertEquals(1, unresolved.size());
        assertTrue(unresolved.getFirst().startsWith("probe.Sub (IncompatibleClassChangeError: "), unresolved.getFirst());
    }

    @Test
    void theScriptsOwnClassesAreNotReferencesOutsideIt() {
        ScriptReferences references = ScriptReferences.read(SCRIPT);

        assertTrue(references.classes().contains("com.example.Missing"));
        assertTrue(references.classes().contains("java.util.function.Consumer"), "a parameter type counts");
        assertFalse(references.classes().contains("probe.Helper"));
    }

    /** A class whose static {@code run()} and {@code help()} hold {@code body}. */
    private static byte[] type(String name, Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        for (String method : List.of("run", "help")) {
            MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, method, "()V", null, null);
            visitor.visitCode();
            if (method.equals("run")) body.accept(visitor);
            visitor.visitInsn(Opcodes.RETURN);
            visitor.visitMaxs(0, 0);
            visitor.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}
