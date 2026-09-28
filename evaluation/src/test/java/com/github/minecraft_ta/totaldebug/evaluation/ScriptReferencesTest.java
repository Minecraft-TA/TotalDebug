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
    void aMemberTheServerHasButNotAsTheScriptUsesItIsNamedWithTheReason() {
        Handle linker = new Handle(Opcodes.H_INVOKESTATIC, Type.getInternalName(ScriptAccessLinker.class), "bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;", false);
        Map<String, byte[]> script = Map.of("probe.Kinds", type("probe/Kinds", method -> {
            method.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Integer", "value", "I");
            method.visitInsn(Opcodes.POP);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "length", "()I", false);
            method.visitInsn(Opcodes.POP);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitInvokeDynamicInsn("valueOf", "(Ljava/lang/Integer;I)Ljava/lang/Integer;", linker,
                    "java.lang.Integer", "valueOf", "(I)Ljava/lang/Integer;", ScriptAccessLinker.INVOKE_VIRTUAL);
            method.visitInsn(Opcodes.POP);
            method.visitInsn(Opcodes.ICONST_0);
            method.visitInvokeDynamicInsn("MAX_VALUE", "(I)V", linker,
                    "java.lang.Integer", "MAX_VALUE", "I", ScriptAccessLinker.PUT_STATIC);
        }));

        assertEquals(List.of("java.lang.Integer.value (not static on the server)",
                        "java.lang.String.length() (not static on the server)",
                        "java.lang.Integer.valueOf(int) (static on the server)",
                        "java.lang.Integer.MAX_VALUE (unexpected set of a final field)"),
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
    void membersResolveFromTheScriptClassThatUsesThemAsTheJvmWould() {
        ClassWriter list = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        list.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "probe/Trimmed", null, "java/util/ArrayList", null);
        MethodVisitor constructor = list.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor trim = list.visitMethod(Opcodes.ACC_PUBLIC, "trim", "()V", null, null);
        trim.visitCode();
        trim.visitVarInsn(Opcodes.ALOAD, 0);
        trim.visitInsn(Opcodes.ICONST_0);
        trim.visitInsn(Opcodes.ICONST_1);
        // A protected method of the platform superclass, called on this subclass.
        trim.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "removeRange", "(II)V", false);
        trim.visitInsn(Opcodes.RETURN);
        trim.visitMaxs(0, 0);
        trim.visitEnd();
        list.visitEnd();

        ClassWriter loader = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        loader.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "probe/Loader", null, "java/lang/ClassLoader", null);
        MethodVisitor hidden = loader.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        hidden.visitCode();
        hidden.visitVarInsn(Opcodes.ALOAD, 0);
        hidden.visitInsn(Opcodes.ACONST_NULL);
        hidden.visitInsn(Opcodes.ACONST_NULL);
        hidden.visitInsn(Opcodes.ACONST_NULL);
        // ClassLoader's private constructor, which no subclass may call.
        hidden.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/ClassLoader", "<init>",
                "(Ljava/lang/Void;Ljava/lang/String;Ljava/lang/ClassLoader;)V", false);
        hidden.visitInsn(Opcodes.RETURN);
        hidden.visitMaxs(0, 0);
        hidden.visitEnd();
        loader.visitEnd();

        assertEquals(List.of("new java.lang.ClassLoader(Void, String, ClassLoader) (not accessible to a subclass)"),
                ScriptReferences.read(Map.of("probe.Trimmed", list.toByteArray(), "probe.Loader", loader.toByteArray()))
                        .unresolved(ScriptReferencesTest.class.getClassLoader()),
                "the protected super call and the accessible superclass constructor link");
    }

    @Test
    void whatTheJvmChecksWhenLinkingAnInstructionIsCheckedToo() {
        Handle linker = new Handle(Opcodes.H_INVOKESTATIC, Type.getInternalName(ScriptAccessLinker.class), "bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;", false);
        Map<String, byte[]> script = Map.of("probe.Instructions", type("probe/Instructions", method -> {
            // A package-private class named only by a cast.
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/AbstractStringBuilder");
            method.visitInsn(Opcodes.POP);
            // An abstract class allocated, directly and through the linker.
            method.visitTypeInsn(Opcodes.NEW, "java/lang/Number");
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Number", "<init>", "()V", false);
            method.visitInsn(Opcodes.POP);
            method.visitInvokeDynamicInsn("newInstance", "()Ljava/lang/Number;", linker,
                    "java.lang.Number", "<init>", "()V", ScriptAccessLinker.NEW_INSTANCE);
            method.visitInsn(Opcodes.POP);
            // An interface method called as if its owner were a class.
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/List", "size", "()I", false);
            method.visitInsn(Opcodes.POP);
        }));

        assertEquals(List.of("java.lang.AbstractStringBuilder (not accessible)",
                        "new java.lang.Number() (abstract on the server)",
                        "java.util.List.size() (an interface on the server)"),
                ScriptReferences.read(script).unresolved(ScriptReferencesTest.class.getClassLoader()),
                "the linker's allocation is named once with the plain one, as the same member");
    }

    @Test
    void theJvmVerifiesTheScriptsCodeAgainstTheClassesItHas() {
        Map<String, byte[]> script = Map.of("probe.Mistyped", type("probe/Mistyped", method -> {
            method.visitTypeInsn(Opcodes.NEW, "java/lang/Thread");
            method.visitInsn(Opcodes.DUP);
            // A String where the constructor takes a ThreadGroup: only verification sees it. The verifier treats an
            // interface type as Object, so it is a class here.
            method.visitLdcInsn("not a group");
            method.visitLdcInsn("name");
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Thread", "<init>", "(Ljava/lang/ThreadGroup;Ljava/lang/String;)V", false);
            method.visitInsn(Opcodes.POP);
        }));

        List<String> unresolved = ScriptReferences.read(script).unresolved(ScriptReferencesTest.class.getClassLoader());

        assertEquals(1, unresolved.size(), unresolved.toString());
        assertTrue(unresolved.getFirst().startsWith("probe.Mistyped (VerifyError: "), unresolved.getFirst());
    }

    @Test
    void aScriptClassNamedLikeTheProbeIsStillChecked() {
        Map<String, byte[]> script = Map.of("TotalDebug$LinkProbe", type("TotalDebug$LinkProbe", method -> {
            method.visitTypeInsn(Opcodes.NEW, "com/example/Missing");
            method.visitInsn(Opcodes.POP);
        }));

        assertEquals(List.of("com.example.Missing"),
                ScriptReferences.read(script).unresolved(ScriptReferencesTest.class.getClassLoader()));
    }

    @Test
    void aMemberNamedThroughTheScriptsOwnClassIsResolvedWhereItIsInherited() {
        ClassWriter list = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        list.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "probe/Inherits", null, "java/util/ArrayList", null);
        MethodVisitor calls = list.visitMethod(Opcodes.ACC_PUBLIC, "calls", "()V", null, null);
        calls.visitCode();
        calls.visitVarInsn(Opcodes.ALOAD, 0);
        calls.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "probe/Inherits", "size", "()I", false);
        calls.visitInsn(Opcodes.POP);
        calls.visitVarInsn(Opcodes.ALOAD, 0);
        calls.visitInsn(Opcodes.ACONST_NULL);
        // javac names the script class for an inherited member, as for ScriptProgram's logln.
        calls.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "probe/Inherits", "logln", "(Ljava/lang/Object;)V", false);
        calls.visitInsn(Opcodes.RETURN);
        calls.visitMaxs(0, 0);
        calls.visitEnd();
        list.visitEnd();

        assertEquals(List.of("probe.Inherits.logln(Object)"),
                ScriptReferences.read(Map.of("probe.Inherits", list.toByteArray()))
                        .unresolved(ScriptReferencesTest.class.getClassLoader()));
    }

    @Test
    void aProtectedStaticMethodNamedThroughASiblingSubclassLinksAsTheJvmAllows() {
        ClassWriter action = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        action.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "probe/Action", null,
                "java/util/concurrent/RecursiveAction", null);
        MethodVisitor peek = action.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "peek", "()V", null, null);
        peek.visitCode();
        // Declared by ForkJoinTask, which Action extends; named through RecursiveTask, which it does not.
        peek.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/concurrent/RecursiveTask", "peekNextLocalTask",
                "()Ljava/util/concurrent/ForkJoinTask;", false);
        peek.visitInsn(Opcodes.POP);
        peek.visitInsn(Opcodes.RETURN);
        peek.visitMaxs(0, 0);
        peek.visitEnd();
        action.visitEnd();

        assertEquals(List.of(), ScriptReferences.read(Map.of("probe.Action", action.toByteArray()))
                .unresolved(ScriptReferencesTest.class.getClassLoader()));
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
