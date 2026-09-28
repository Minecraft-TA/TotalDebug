package com.github.minecraft_ta.totaldebug.evaluation;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The classes, fields and methods a script's compiled classes refer to, read from their bytecode, and which of them do
 * not link under a class loader. The loader and the JVM decide, not rules of this class: classes are loaded without
 * initializing them, the script's own classes are defined so the JVM checks their supertypes, a member the compiler
 * routed through {@link ScriptAccessLinker} is linked by the linker's own bootstrap, and any other member is looked up
 * from the script's position with {@link MethodHandles.Lookup}, which resolves as the matching instruction does.
 */
public final class ScriptReferences {
    private static final String LINKER = Type.getInternalName(ScriptAccessLinker.class);
    private static final String PROBE = "TotalDebug$LinkProbe";

    /**
     * A member and how the script uses it, as a {@link ScriptAccessLinker} operation. {@code callSite} is the type of the
     * linker call site that uses it, or null for an instruction that uses it directly.
     */
    private record Member(String owner, String name, String descriptor, int operation, String callSite) {
    }

    private final Map<String, byte[]> script;
    private final Set<String> classes = new LinkedHashSet<>();
    private final Set<Member> members = new LinkedHashSet<>();

    private ScriptReferences(Map<String, byte[]> script) {
        this.script = Map.copyOf(script);
    }

    /** Reads what {@code script}, the compiled classes by binary name, refers to. */
    public static ScriptReferences read(Map<String, byte[]> script) {
        Objects.requireNonNull(script, "script");
        ScriptReferences references = new ScriptReferences(script);
        for (byte[] bytes : script.values()) {
            new ClassReader(bytes).accept(references.new Reader(), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        references.classes.removeAll(script.keySet());
        return references;
    }

    /** The classes referred to outside the script, by binary name. */
    public Set<String> classes() {
        return Set.copyOf(this.classes);
    }

    /**
     * What does not link when the script runs under {@code parent}: classes, then the script's own classes the JVM
     * refuses to define, then fields and methods, each as Java would name it, with the JVM's reason when the member
     * exists but cannot be linked as the script uses it.
     */
    public List<String> unresolved(ClassLoader parent) {
        Objects.requireNonNull(parent, "parent");
        Map<String, byte[]> definitions = new HashMap<>(this.script);
        String probe = probeName();
        definitions.put(probe, probe(probe));
        ClassLoader loader = new ScriptClassLoader(parent, definitions);
        List<String> unresolved = new ArrayList<>();
        for (String name : this.classes) {
            if (failure(name, loader) != null) unresolved.add(name);
        }
        if (unresolved.isEmpty()) {
            // Defining a class checks its superclass and interfaces: not final, the right kind, accessible.
            for (String name : this.script.keySet().stream().sorted().toList()) {
                String refused = failure(name, loader);
                if (refused != null) unresolved.add(name + " (" + refused + ")");
            }
        }
        MethodHandles.Lookup lookup;
        try {
            lookup = (MethodHandles.Lookup) loader.loadClass(probe).getMethod("lookup").invoke(null);
        } catch (ReflectiveOperationException unexpected) {
            throw new IllegalStateException("Unable to look up members from the script's position", unexpected);
        }
        for (Member member : this.members) {
            String problem = problem(member, lookup, loader);
            if (problem != null) unresolved.add(problem.isEmpty() ? display(member) : display(member) + " (" + problem + ")");
        }
        return List.copyOf(unresolved);
    }

    /** Null when {@code name} loads, otherwise why not. */
    private static String failure(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return null;
        } catch (ClassNotFoundException missing) {
            return "not found";
        } catch (LinkageError refused) {
            return refused.getClass().getSimpleName() + ": " + refused.getMessage();
        }
    }

    /**
     * Null when {@code member} links or depends on a class already named, empty when it does not exist, otherwise why
     * the JVM refused it.
     */
    private static String problem(Member member, MethodHandles.Lookup lookup, ClassLoader loader) {
        try {
            if (member.callSite() != null) {
                ScriptAccessLinker.bootstrap(lookup, member.name(), MethodType.fromMethodDescriptorString(member.callSite(), loader),
                        member.owner(), member.name(), member.descriptor(), member.operation());
            } else {
                lookUp(member, lookup, loader);
            }
            return null;
        } catch (ClassNotFoundException | TypeNotPresentException | NoClassDefFoundError dependsOnMissing) {
            return null;
        } catch (NoSuchMethodException | NoSuchFieldException missing) {
            return "";
        } catch (IllegalAccessException refused) {
            String reason = String.valueOf(refused.getMessage());
            // The JVM found the name and type, but not as the instruction uses it: static where the script expects an
            // instance member, or the reverse.
            if (reason.startsWith("no such") || reason.startsWith("expected a")) {
                return isStatic(member) ? "not static on the server" : "static on the server";
            }
            return reason.contains(":") ? reason.substring(0, reason.indexOf(':')) : reason;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException refused) {
            return refused.getClass().getSimpleName() + ": " + refused.getMessage();
        }
    }

    /** Looks up {@code member} as the instruction that uses it resolves it. */
    private static void lookUp(Member member, MethodHandles.Lookup lookup, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(member.owner(), false, loader);
        switch (member.operation()) {
            case ScriptAccessLinker.GET_FIELD -> lookup.findGetter(owner, member.name(), fieldType(member, loader));
            case ScriptAccessLinker.PUT_FIELD -> lookup.findSetter(owner, member.name(), fieldType(member, loader));
            case ScriptAccessLinker.GET_STATIC -> lookup.findStaticGetter(owner, member.name(), fieldType(member, loader));
            case ScriptAccessLinker.PUT_STATIC -> lookup.findStaticSetter(owner, member.name(), fieldType(member, loader));
            case ScriptAccessLinker.INVOKE_STATIC -> lookup.findStatic(owner, member.name(), methodType(member, loader));
            // Only the script's own subclass could name the special caller, and it is no different to look up.
            case ScriptAccessLinker.INVOKE_VIRTUAL, ScriptAccessLinker.INVOKE_SPECIAL ->
                    lookup.findVirtual(owner, member.name(), methodType(member, loader));
            default -> {
                // A constructor called directly is either allocated here or a superclass constructor the script's own
                // constructor calls, which a lookup from outside that subclass cannot reach; its existence decides.
                for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
                    if (Type.getConstructorDescriptor(constructor).equals(member.descriptor())) return;
                }
                throw new NoSuchMethodException(member.owner() + ".<init>" + member.descriptor());
            }
        }
    }

    private static boolean isStatic(Member member) {
        return member.operation() == ScriptAccessLinker.GET_STATIC || member.operation() == ScriptAccessLinker.PUT_STATIC
                || member.operation() == ScriptAccessLinker.INVOKE_STATIC;
    }

    private static Class<?> fieldType(Member member, ClassLoader loader) {
        return MethodType.fromMethodDescriptorString("()" + member.descriptor(), loader).returnType();
    }

    private static MethodType methodType(Member member, ClassLoader loader) {
        return MethodType.fromMethodDescriptorString(member.descriptor(), loader);
    }

    /** A class next to the script's that hands out a lookup from the script's package; no script code runs. */
    private String probeName() {
        String any = this.script.keySet().stream().sorted().findFirst().orElse("");
        int dot = any.lastIndexOf('.');
        return dot < 0 ? PROBE : any.substring(0, dot + 1) + PROBE;
    }

    private static byte[] probe(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, name.replace('.', '/'), null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String display(Member member) {
        boolean field = member.operation() <= ScriptAccessLinker.PUT_STATIC;
        if (field) return member.owner() + "." + member.name();
        String parameters = Arrays.stream(Type.getArgumentTypes(member.descriptor()))
                .map(type -> type.getClassName().substring(type.getClassName().lastIndexOf('.') + 1))
                .collect(Collectors.joining(", "));
        return member.name().equals("<init>") ? "new " + member.owner() + "(" + parameters + ")"
                : member.owner() + "." + member.name() + "(" + parameters + ")";
    }

    /** The {@link ScriptAccessLinker} operation matching a field or method instruction, or a method handle's tag. */
    private static int operation(int opcode, String name) {
        return switch (opcode) {
            case Opcodes.GETFIELD, Opcodes.H_GETFIELD -> ScriptAccessLinker.GET_FIELD;
            case Opcodes.PUTFIELD, Opcodes.H_PUTFIELD -> ScriptAccessLinker.PUT_FIELD;
            case Opcodes.GETSTATIC, Opcodes.H_GETSTATIC -> ScriptAccessLinker.GET_STATIC;
            case Opcodes.PUTSTATIC, Opcodes.H_PUTSTATIC -> ScriptAccessLinker.PUT_STATIC;
            case Opcodes.INVOKESTATIC, Opcodes.H_INVOKESTATIC -> ScriptAccessLinker.INVOKE_STATIC;
            case Opcodes.H_NEWINVOKESPECIAL -> ScriptAccessLinker.NEW_INSTANCE;
            case Opcodes.INVOKESPECIAL, Opcodes.H_INVOKESPECIAL ->
                    name.equals("<init>") ? ScriptAccessLinker.NEW_INSTANCE : ScriptAccessLinker.INVOKE_SPECIAL;
            default -> ScriptAccessLinker.INVOKE_VIRTUAL;
        };
    }

    private void type(Type type) {
        switch (type.getSort()) {
            case Type.ARRAY -> type(type.getElementType());
            case Type.OBJECT -> this.classes.add(type.getClassName());
            case Type.METHOD -> {
                for (Type argument : type.getArgumentTypes()) type(argument);
                type(type.getReturnType());
            }
            default -> {
            }
        }
    }

    /** An internal name or, for an array, a descriptor, as instructions name their types. */
    private void internalName(String name) {
        if (name != null) type(name.startsWith("[") ? Type.getType(name) : Type.getObjectType(name));
    }

    private void member(String owner, String name, String descriptor, int operation, String callSite) {
        internalName(owner);
        type(Type.getType(descriptor));
        // Arrays answer only length and Object's methods; the script's own members were compiled with it.
        String binary = owner.replace('/', '.');
        if (owner.startsWith("[") || this.script.containsKey(binary)) return;
        this.members.add(new Member(binary, name, descriptor, operation, callSite));
    }

    /** A call site {@link ScriptBytecodeTransformer} made for a member: its declaring class, name, descriptor and use. */
    private boolean linked(String callSite, Handle bootstrap, Object[] arguments) {
        if (!bootstrap.getOwner().equals(LINKER) || arguments.length != 4 || !(arguments[0] instanceof String declaration)
                || !(arguments[1] instanceof String name) || !(arguments[2] instanceof String descriptor)
                || !(arguments[3] instanceof Integer operation)) {
            return false;
        }
        String owner = declaration.replace('.', '/');
        if (operation == ScriptAccessLinker.INITIALIZE_CLASS) internalName(owner);
        else member(owner, name, descriptor, operation, callSite);
        return true;
    }

    private void constant(Object value) {
        switch (value) {
            case Type type -> type(type);
            case Handle handle -> member(handle.getOwner(), handle.getName(), handle.getDesc(),
                    operation(handle.getTag(), handle.getName()), null);
            case ConstantDynamic dynamic -> {
                type(Type.getType(dynamic.getDescriptor()));
                constant(dynamic.getBootstrapMethod());
                for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                    constant(dynamic.getBootstrapMethodArgument(index));
                }
            }
            default -> {
            }
        }
    }

    private final class Reader extends ClassVisitor {
        private Reader() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            internalName(superName);
            if (interfaces != null) for (String implemented : interfaces) internalName(implemented);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            type(Type.getType(descriptor));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            type(Type.getMethodType(descriptor));
            if (exceptions != null) for (String exception : exceptions) internalName(exception);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitTypeInsn(int opcode, String type) {
                    internalName(type);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    member(owner, name, descriptor, operation(opcode, name), null);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    member(owner, name, descriptor, operation(opcode, name), null);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                    type(Type.getMethodType(descriptor));
                    if (linked(descriptor, bootstrap, arguments)) return;
                    constant(bootstrap);
                    for (Object argument : arguments) constant(argument);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    constant(value);
                }

                @Override
                public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                    type(Type.getType(descriptor));
                }

                @Override
                public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                    internalName(type);
                }
            };
        }
    }
}
