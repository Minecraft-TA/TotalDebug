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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The classes, fields and methods a script's compiled classes refer to, read from their bytecode: which script class
 * refers to each and how, as the JVM will link it. {@link #unresolved} asks a class loader which of them do not link.
 */
public final class ScriptReferences {
    private static final String LINKER = Type.getInternalName(ScriptAccessLinker.class);
    /** A superclass or own constructor called from a constructor, rather than an allocation. */
    static final int SUPER_CONSTRUCTOR = -1;

    /**
     * A member and how the script uses it, as a {@link ScriptAccessLinker} operation or {@link #SUPER_CONSTRUCTOR}.
     * {@code callSite} is the type of the linker call site that uses it, or null for an instruction or method handle
     * that uses it directly; {@code referrer} is the script class whose code does.
     */
    record Member(String owner, String name, String descriptor, int operation, String callSite, String referrer,
                  boolean isInterface) {
        boolean isField() {
            return this.operation >= ScriptAccessLinker.GET_FIELD && this.operation <= ScriptAccessLinker.PUT_STATIC;
        }

        boolean isStatic() {
            return this.operation == ScriptAccessLinker.GET_STATIC || this.operation == ScriptAccessLinker.PUT_STATIC
                    || this.operation == ScriptAccessLinker.INVOKE_STATIC;
        }

        boolean isInvocation() {
            return this.operation == ScriptAccessLinker.INVOKE_STATIC || this.operation == ScriptAccessLinker.INVOKE_VIRTUAL
                    || this.operation == ScriptAccessLinker.INVOKE_SPECIAL;
        }

        /** As Java names it. */
        String display() {
            if (isField()) return this.owner + "." + this.name;
            String parameters = Arrays.stream(Type.getArgumentTypes(this.descriptor))
                    .map(type -> type.getClassName().substring(type.getClassName().lastIndexOf('.') + 1))
                    .collect(Collectors.joining(", "));
            return this.name.equals("<init>") ? "new " + this.owner + "(" + parameters + ")"
                    : this.owner + "." + this.name + "(" + parameters + ")";
        }
    }

    /** A type an instruction of {@code referrer} names, which the JVM resolves with an access check. */
    record TypeUse(String type, String referrer) {
    }

    /**
     * An invokedynamic site of {@code referrer} whose bootstrap is the JDK's own, such as a lambda's or a string
     * concatenation's. Running it links the site as the JVM would, and runs no script code.
     */
    record Dynamic(String referrer, String name, String descriptor, Handle bootstrap, List<Object> arguments) {
        boolean isLambda() {
            return this.bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory");
        }

        String display() {
            if (isLambda()) return "lambda for " + Type.getReturnType(this.descriptor).getClassName() + "." + this.name + " in " + this.referrer;
            String kind = this.bootstrap.getOwner().substring(this.bootstrap.getOwner().lastIndexOf('/') + 1);
            return kind + " call site " + this.name + " in " + this.referrer;
        }
    }

    private final Map<String, byte[]> script;
    private final Set<String> classes = new LinkedHashSet<>();
    private final Set<TypeUse> typeUses = new LinkedHashSet<>();
    private final Set<Member> members = new LinkedHashSet<>();
    private final List<Dynamic> dynamics = new ArrayList<>();

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
     * What does not link when the script runs under {@code parent}, each as Java would name it, with the JVM's reason
     * when the reference exists but cannot be linked as the script uses it. See {@link ScriptLinkCheck}.
     */
    public List<String> unresolved(ClassLoader parent) {
        return new ScriptLinkCheck(this, Objects.requireNonNull(parent, "parent")).unresolved();
    }

    /** The script's compiled classes by binary name. */
    Map<String, byte[]> script() {
        return this.script;
    }

    Set<TypeUse> typeUses() {
        return this.typeUses;
    }

    Set<Member> members() {
        return this.members;
    }

    List<Dynamic> dynamics() {
        return this.dynamics;
    }

    /**
     * The {@link ScriptAccessLinker} operation matching a field or method instruction, or a method handle's tag. A
     * constructor call counts as an allocation until {@link Reader} finds it allocates nothing.
     */
    static int operation(int opcode, String name) {
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

    private void member(String referrer, String owner, String name, String descriptor, int operation, String callSite,
                        boolean isInterface) {
        internalName(owner);
        type(Type.getType(descriptor));
        // Arrays answer only length and Object's methods. A member named through a script class is resolved too, since
        // it may be inherited from a class the server has another version of; only a constructor it calls on one of
        // the script's own classes shares their runtime package, where the JVM allows every constructor.
        String binary = owner.replace('/', '.');
        if (owner.startsWith("[") || operation == SUPER_CONSTRUCTOR && this.script.containsKey(binary)) return;
        this.members.add(new Member(binary, name, descriptor, operation, callSite, referrer, isInterface));
    }

    /** The types of a method descriptor an instruction of {@code referrer} names, which the JVM resolves with access. */
    private void descriptorUses(String referrer, Type method) {
        for (Type argument : method.getArgumentTypes()) typeUse(referrer, argument);
        typeUse(referrer, method.getReturnType());
    }

    private void typeUse(String referrer, Type type) {
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) typeUse(referrer, type.getInternalName());
    }

    /** A type an instruction of {@code referrer} names: an internal name, or a descriptor for an array. */
    private void typeUse(String referrer, String name) {
        internalName(name);
        Type type = name.startsWith("[") ? Type.getType(name).getElementType() : Type.getObjectType(name);
        if (type.getSort() != Type.OBJECT || this.script.containsKey(type.getClassName())) return;
        this.typeUses.add(new TypeUse(type.getClassName(), referrer));
    }

    /** A call site {@link ScriptBytecodeTransformer} made for a member: its declaring class, name, descriptor and use. */
    private boolean linked(String referrer, String callSite, Handle bootstrap, Object[] arguments) {
        if (!bootstrap.getOwner().equals(LINKER) || arguments.length != 4 || !(arguments[0] instanceof String declaration)
                || !(arguments[1] instanceof String name) || !(arguments[2] instanceof String descriptor)
                || !(arguments[3] instanceof Integer operation)) {
            return false;
        }
        String owner = declaration.replace('.', '/');
        if (operation == ScriptAccessLinker.INITIALIZE_CLASS) internalName(owner);
        else member(referrer, owner, name, descriptor, operation, callSite, false);
        return true;
    }

    private void constant(String referrer, Object value) {
        switch (value) {
            case Type type when type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY -> typeUse(referrer, type.getInternalName());
            case Type type when type.getSort() == Type.METHOD -> {
                type(type);
                descriptorUses(referrer, type);
            }
            case Type type -> type(type);
            case Handle handle -> member(referrer, handle.getOwner(), handle.getName(), handle.getDesc(),
                    operation(handle.getTag(), handle.getName()), null, handle.isInterface());
            case ConstantDynamic dynamic -> {
                type(Type.getType(dynamic.getDescriptor()));
                constant(referrer, dynamic.getBootstrapMethod());
                for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                    constant(referrer, dynamic.getBootstrapMethodArgument(index));
                }
            }
            default -> {
            }
        }
    }

    private final class Reader extends ClassVisitor {
        private String referrer;

        private Reader() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.referrer = name.replace('/', '.');
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
            String referrer = this.referrer;
            return new MethodVisitor(Opcodes.ASM9) {
                /** The types allocated and not yet constructed, innermost first: a constructor call pairs with one. */
                private final ArrayDeque<String> allocations = new ArrayDeque<>();

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    typeUse(referrer, type);
                    if (opcode == Opcodes.NEW) this.allocations.push(type);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    member(referrer, owner, name, descriptor, operation(opcode, name), null, false);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    int operation = operation(opcode, name);
                    if (operation == ScriptAccessLinker.NEW_INSTANCE) {
                        if (owner.equals(this.allocations.peek())) this.allocations.pop();
                        else operation = SUPER_CONSTRUCTOR;
                    }
                    member(referrer, owner, name, descriptor, operation, null, isInterface);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                    type(Type.getMethodType(descriptor));
                    // Resolving a call site resolves its type, with an access check for each class it names.
                    descriptorUses(referrer, Type.getMethodType(descriptor));
                    if (linked(referrer, descriptor, bootstrap, arguments)) return;
                    constant(referrer, bootstrap);
                    for (Object argument : arguments) constant(referrer, argument);
                    boolean jdk = bootstrap.getOwner().startsWith("java/lang/invoke/") || bootstrap.getOwner().startsWith("java/lang/runtime/");
                    if (jdk && Arrays.stream(arguments).noneMatch(ConstantDynamic.class::isInstance)) {
                        dynamics.add(new Dynamic(referrer, name, descriptor, bootstrap, List.of(arguments)));
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    constant(referrer, value);
                }

                @Override
                public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                    typeUse(referrer, descriptor);
                }

                @Override
                public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                    if (type != null) typeUse(referrer, type);
                }
            };
        }
    }
}
