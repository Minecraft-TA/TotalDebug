package com.github.minecraft_ta.totaldebug.evaluation;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The classes, fields and methods a script's compiled classes refer to, read from their bytecode, and which of them a
 * class loader cannot resolve. Resolving loads classes without initializing them, so it answers from the classes a
 * runtime actually has rather than the files they came from. A member the compiler routed through
 * {@link ScriptAccessLinker} counts as the member its call site names.
 */
public final class ScriptReferences {
    private static final String LINKER = Type.getInternalName(ScriptAccessLinker.class);

    /** How a script uses a member: the JVM links an instruction only to a member of that kind. */
    private enum Use {
        INSTANCE_FIELD, STATIC_FIELD, INSTANCE_METHOD, STATIC_METHOD, CONSTRUCTOR, NEW_INSTANCE;

        boolean method() {
            return this != INSTANCE_FIELD && this != STATIC_FIELD;
        }

        boolean isStatic() {
            return this == STATIC_FIELD || this == STATIC_METHOD;
        }

        /** The use of a {@link ScriptAccessLinker} operation. */
        static Use of(int operation) {
            return switch (operation) {
                case ScriptAccessLinker.GET_FIELD, ScriptAccessLinker.PUT_FIELD -> INSTANCE_FIELD;
                case ScriptAccessLinker.GET_STATIC, ScriptAccessLinker.PUT_STATIC -> STATIC_FIELD;
                case ScriptAccessLinker.INVOKE_STATIC -> STATIC_METHOD;
                case ScriptAccessLinker.NEW_INSTANCE -> NEW_INSTANCE;
                default -> INSTANCE_METHOD;
            };
        }

        /** The use of a field or method instruction, or of a method handle's tag. */
        static Use ofInstruction(int opcode, String name) {
            return switch (opcode) {
                case Opcodes.GETFIELD, Opcodes.PUTFIELD, Opcodes.H_GETFIELD, Opcodes.H_PUTFIELD -> INSTANCE_FIELD;
                case Opcodes.GETSTATIC, Opcodes.PUTSTATIC, Opcodes.H_GETSTATIC, Opcodes.H_PUTSTATIC -> STATIC_FIELD;
                case Opcodes.INVOKESTATIC, Opcodes.H_INVOKESTATIC -> STATIC_METHOD;
                case Opcodes.H_NEWINVOKESPECIAL -> NEW_INSTANCE;
                default -> name.equals("<init>") ? CONSTRUCTOR : INSTANCE_METHOD;
            };
        }
    }

    private record Member(String owner, String name, String descriptor, Use use) {
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
     * What {@code parent}, the loader the script would run under, cannot resolve: classes, then the script's own classes
     * the JVM refuses to define over them, then fields and methods that are missing or of another kind, each as Java
     * would name it.
     */
    public List<String> unresolved(ClassLoader parent) {
        ClassLoader loader = new ScriptClassLoader(Objects.requireNonNull(parent, "parent"), this.script);
        List<String> unresolved = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>();
        for (String name : this.classes) {
            if (load(name, loader) instanceof Failed) {
                missing.add(name);
                unresolved.add(name);
            }
        }
        if (missing.isEmpty()) {
            // Defining a class checks its superclass and interfaces: not final, the right kind, accessible.
            for (String name : this.script.keySet().stream().sorted().toList()) {
                if (load(name, loader) instanceof Failed failed) unresolved.add(name + " (" + failed.reason() + ")");
            }
        }
        for (Member member : this.members) {
            if (missing.contains(member.owner()) || !(load(member.owner(), loader) instanceof Loaded(Class<?> owner))) continue;
            String problem;
            try {
                problem = member.use().method() ? methodProblem(owner, member) : fieldProblem(owner, member);
            } catch (LinkageError inspection) {
                problem = "cannot be inspected: " + inspection;
            }
            if (problem != null) unresolved.add(problem.isEmpty() ? display(member) : display(member) + " (" + problem + ")");
        }
        return List.copyOf(unresolved);
    }

    private sealed interface Loading permits Loaded, Failed {
    }

    private record Loaded(Class<?> type) implements Loading {
    }

    private record Failed(String reason) implements Loading {
    }

    private static Loading load(String name, ClassLoader loader) {
        try {
            return new Loaded(Class.forName(name, false, loader));
        } catch (ClassNotFoundException missing) {
            return new Failed("not found");
        } catch (LinkageError refused) {
            return new Failed(refused.getClass().getSimpleName() + ": " + refused.getMessage());
        }
    }

    /** Empty when the field is missing, a reason when it has another kind, null when it links. */
    private static String fieldProblem(Class<?> owner, Member member) {
        Field field = field(owner, member.name(), member.descriptor());
        if (field == null) return "";
        boolean isStatic = Modifier.isStatic(field.getModifiers());
        if (isStatic == member.use().isStatic()) return null;
        return isStatic ? "static on the server" : "not static on the server";
    }

    /** Empty when the method is missing, a reason when it has another kind, null when it links. */
    private static String methodProblem(Class<?> owner, Member member) {
        if (member.name().equals("<init>")) {
            for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
                if (!Type.getConstructorDescriptor(constructor).equals(member.descriptor())) continue;
                if (member.use() == Use.NEW_INSTANCE && (owner.isInterface() || Modifier.isAbstract(owner.getModifiers()))) {
                    return "abstract on the server";
                }
                return null;
            }
            return "";
        }
        Method method = method(owner, member.name(), member.descriptor());
        if (method == null) return "";
        if (signaturePolymorphic(method)) return null;
        boolean isStatic = Modifier.isStatic(method.getModifiers());
        if (isStatic == member.use().isStatic()) return null;
        return isStatic ? "static on the server" : "not static on the server";
    }

    private static Field field(Class<?> type, String name, String descriptor) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getName().equals(name) && Type.getDescriptor(field.getType()).equals(descriptor)) return field;
        }
        for (Class<?> implemented : type.getInterfaces()) {
            Field inherited = field(implemented, name, descriptor);
            if (inherited != null) return inherited;
        }
        return type.getSuperclass() == null ? null : field(type.getSuperclass(), name, descriptor);
    }

    private static Method method(Class<?> owner, String name, String descriptor) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                if (Type.getMethodDescriptor(method).equals(descriptor) || signaturePolymorphic(method)) return method;
            }
        }
        Method inherited = interfaceMethod(owner, name, descriptor);
        if (inherited != null) return inherited;
        return owner.isInterface() ? method(Object.class, name, descriptor) : null;
    }

    private static Method interfaceMethod(Class<?> type, String name, String descriptor) {
        for (Class<?> implemented : type.getInterfaces()) {
            for (Method method : implemented.getDeclaredMethods()) {
                if (method.getName().equals(name) && Type.getMethodDescriptor(method).equals(descriptor)) return method;
            }
            Method inherited = interfaceMethod(implemented, name, descriptor);
            if (inherited != null) return inherited;
        }
        return type.getSuperclass() == null ? null : interfaceMethod(type.getSuperclass(), name, descriptor);
    }

    /** {@code MethodHandle.invoke} and the like take any descriptor at their call site. */
    private static boolean signaturePolymorphic(Method method) {
        Class<?> owner = method.getDeclaringClass();
        return (owner.getName().equals("java.lang.invoke.MethodHandle") || owner.getName().equals("java.lang.invoke.VarHandle"))
                && Modifier.isNative(method.getModifiers()) && method.isVarArgs();
    }

    private static String display(Member member) {
        if (!member.use().method()) return member.owner() + "." + member.name();
        String parameters = Arrays.stream(Type.getArgumentTypes(member.descriptor()))
                .map(type -> type.getClassName().substring(type.getClassName().lastIndexOf('.') + 1))
                .collect(Collectors.joining(", "));
        return member.name().equals("<init>") ? "new " + member.owner() + "(" + parameters + ")"
                : member.owner() + "." + member.name() + "(" + parameters + ")";
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

    private void member(String owner, String name, String descriptor, Use use) {
        internalName(owner);
        type(Type.getType(descriptor));
        // Arrays answer only length and Object's methods.
        if (!owner.startsWith("[")) this.members.add(new Member(owner.replace('/', '.'), name, descriptor, use));
    }

    /** A call site {@link ScriptBytecodeTransformer} made for a member: its declaring class, name, descriptor and use. */
    private void linked(Handle bootstrap, Object[] arguments) {
        if (!bootstrap.getOwner().equals(LINKER) || arguments.length != 4 || !(arguments[0] instanceof String declaration)
                || !(arguments[1] instanceof String name) || !(arguments[2] instanceof String descriptor)
                || !(arguments[3] instanceof Integer operation)) {
            return;
        }
        String owner = declaration.replace('.', '/');
        if (operation == ScriptAccessLinker.INITIALIZE_CLASS) internalName(owner);
        else member(owner, name, descriptor, Use.of(operation));
    }

    private void constant(Object value) {
        switch (value) {
            case Type type -> type(type);
            case Handle handle -> member(handle.getOwner(), handle.getName(), handle.getDesc(),
                    Use.ofInstruction(handle.getTag(), handle.getName()));
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
                    member(owner, name, descriptor, Use.ofInstruction(opcode, name));
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    member(owner, name, descriptor, Use.ofInstruction(opcode, name));
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                    type(Type.getMethodType(descriptor));
                    constant(bootstrap);
                    for (Object argument : arguments) constant(argument);
                    linked(bootstrap, arguments);
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
