package com.github.minecraft_ta.totaldebug.script;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import javax.tools.JavaFileManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Rewrites javac-generated script classes; application classes are read only as metadata. */
final class ScriptBytecodeTransformer {
    private static final String LINKER = Type.getInternalName(ScriptAccessLinker.class);
    private static final Handle BOOTSTRAP = new Handle(
            Opcodes.H_INVOKESTATIC,
            LINKER,
            "bootstrap",
            MethodTypeDescriptors.BOOTSTRAP,
            false
    );

    private ScriptBytecodeTransformer() {
    }

    static Map<String, byte[]> transform(Map<String, byte[]> classes, JavaFileManager classpath) {
        Objects.requireNonNull(classes, "classes");
        Objects.requireNonNull(classpath, "classpath");
        Set<String> generatedOwners = classes.keySet().stream()
                .map(name -> name.replace('.', '/'))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        ScriptTypeResolver types = new ScriptTypeResolver(classes, classpath);
        Map<String, byte[]> transformed = new LinkedHashMap<>();
        classes.forEach((name, bytecode) -> transformed.put(
                name,
                transform(bytecode, generatedOwners, types)
        ));
        return Map.copyOf(transformed);
    }

    private static byte[] transform(
            byte[] bytecode,
            Set<String> generatedOwners,
            ScriptTypeResolver types
    ) {
        ClassReader reader = new ClassReader(bytecode);
        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, 0);
        boolean framesChanged = false;
        for (MethodNode method : node.methods) {
            framesChanged |= transformConstructors(method, generatedOwners, types);
        }
        framesChanged |= transformMethodHandles(node, generatedOwners, types);

        ClassWriter writer = new ClassWriter(framesChanged ? ClassWriter.COMPUTE_FRAMES : 0) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return types.commonSuperClass(first, second);
            }
        };
        node.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        String declaration = linkedOwner(owner, name, descriptor, true, generatedOwners, types);
                        if (declaration == null) {
                            super.visitFieldInsn(opcode, owner, name, descriptor);
                            return;
                        }
                        int operation = switch (opcode) {
                            case Opcodes.GETFIELD -> ScriptAccessLinker.GET_FIELD;
                            case Opcodes.PUTFIELD -> ScriptAccessLinker.PUT_FIELD;
                            case Opcodes.GETSTATIC -> ScriptAccessLinker.GET_STATIC;
                            case Opcodes.PUTSTATIC -> ScriptAccessLinker.PUT_STATIC;
                            default -> throw new IllegalArgumentException("Unknown field opcode " + opcode);
                        };
                        super.visitInvokeDynamicInsn(
                                name,
                                fieldCallDescriptor(opcode, owner, descriptor),
                                BOOTSTRAP,
                                declaration.replace('/', '.'),
                                name,
                                descriptor,
                                operation
                        );
                    }

                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String name,
                            String descriptor,
                            boolean isInterface
                    ) {
                        String declaration = "<init>".equals(name) ? null
                                : linkedOwner(owner, name, descriptor, false, generatedOwners, types);
                        if (declaration == null) {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            return;
                        }
                        int operation = switch (opcode) {
                            case Opcodes.INVOKESTATIC -> ScriptAccessLinker.INVOKE_STATIC;
                            case Opcodes.INVOKESPECIAL -> ScriptAccessLinker.INVOKE_SPECIAL;
                            default -> ScriptAccessLinker.INVOKE_VIRTUAL;
                        };
                        super.visitInvokeDynamicInsn(
                                name,
                                methodCallDescriptor(opcode, owner, descriptor),
                                BOOTSTRAP,
                                declaration.replace('/', '.'),
                                name,
                                descriptor,
                                operation
                        );
                    }
                };
            }
        });
        return writer.toByteArray();
    }

    private static boolean transformConstructors(
            MethodNode method,
            Set<String> generatedOwners,
            ScriptTypeResolver types
    ) {
        ArrayDeque<TypeInsnNode> allocations = new ArrayDeque<>();
        boolean changed = false;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                allocations.push(type);
                continue;
            }
            if (!(instruction instanceof MethodInsnNode invocation)
                    || invocation.getOpcode() != Opcodes.INVOKESPECIAL
                    || !"<init>".equals(invocation.name)) {
                continue;
            }
            TypeInsnNode allocation = popAllocation(allocations, invocation.owner);
            if (allocation == null) {
                rejectInaccessibleSuperclassConstructor(invocation, generatedOwners, types);
                continue;
            }
            if (!shouldLink(invocation.owner, generatedOwners)) {
                continue;
            }
            AbstractInsnNode duplicate = nextExecutable(allocation);
            if (!(duplicate instanceof InsnNode) || duplicate.getOpcode() != Opcodes.DUP) {
                continue;
            }
            InvokeDynamicInsnNode replacement = new InvokeDynamicInsnNode(
                    "newInstance",
                    constructorCallDescriptor(invocation.owner, invocation.desc),
                    BOOTSTRAP,
                    invocation.owner.replace('/', '.'),
                    "<init>",
                    invocation.desc,
                    ScriptAccessLinker.NEW_INSTANCE
            );
            // NEW initializes the class before evaluating arguments; the constructor handle runs afterward.
            method.instructions.set(allocation, classInitialization(invocation.owner));
            method.instructions.remove(duplicate);
            method.instructions.set(invocation, replacement);
            instruction = replacement;
            changed = true;
        }
        return changed;
    }

    private static InvokeDynamicInsnNode classInitialization(String owner) {
        return new InvokeDynamicInsnNode(
                "initializeClass",
                "()V",
                BOOTSTRAP,
                owner.replace('/', '.'),
                "<clinit>",
                "()V",
                ScriptAccessLinker.INITIALIZE_CLASS
        );
    }

    private static void rejectInaccessibleSuperclassConstructor(
            MethodInsnNode invocation,
            Set<String> generatedOwners,
            ScriptTypeResolver types
    ) {
        if (!shouldLink(invocation.owner, generatedOwners)) {
            return;
        }
        int access = types.constructorAccess(invocation.owner, invocation.desc);
        if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) {
            String visibility = (access & Opcodes.ACC_PRIVATE) != 0 ? "private" : "package-private";
            throw new TransformationException(
                    "A script subclass cannot call " + visibility + " superclass constructor "
                            + invocation.owner.replace('/', '.') + invocation.desc
            );
        }
    }

    private static boolean transformMethodHandles(ClassNode owner, Set<String> generatedOwners, ScriptTypeResolver types) {
        List<MethodNode> bridges = new ArrayList<>();
        Map<BridgeKey, Handle> replacements = new LinkedHashMap<>();
        Set<String> methodKeys = new HashSet<>();
        owner.methods.forEach(method -> methodKeys.add(method.name + method.desc));
        int nextBridge = 0;

        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null;
                 instruction = instruction.getNext()) {
                if (!(instruction instanceof InvokeDynamicInsnNode dynamic)
                        || !dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
                    continue;
                }
                for (int i = 0; i < dynamic.bsmArgs.length; i++) {
                    if (!(dynamic.bsmArgs[i] instanceof Handle target)) {
                        continue;
                    }
                    String declaration = target.getTag() == Opcodes.H_NEWINVOKESPECIAL
                            ? (shouldLink(target.getOwner(), generatedOwners) ? target.getOwner() : null)
                            : linkedOwner(target.getOwner(), target.getName(), target.getDesc(), false, generatedOwners, types);
                    if (declaration == null) {
                        continue;
                    }
                    String descriptor = handleCallDescriptor(target);
                    Type[] captures = Type.getArgumentTypes(dynamic.desc);
                    if (captures.length > 0 && (target.getTag() == Opcodes.H_INVOKEVIRTUAL
                            || target.getTag() == Opcodes.H_INVOKEINTERFACE
                            || target.getTag() == Opcodes.H_INVOKESPECIAL)) {
                        // Static implementation handles require an exact match for captured receiver types.
                        Type[] arguments = Type.getArgumentTypes(descriptor);
                        arguments[0] = captures[0];
                        descriptor = Type.getMethodDescriptor(Type.getReturnType(descriptor), arguments);
                    }
                    BridgeKey key = new BridgeKey(target, descriptor);
                    Handle replacement = replacements.get(key);
                    if (replacement == null) {
                        String name;
                        do {
                            name = "$totalDebug$access$" + nextBridge++;
                        } while (!methodKeys.add(name + descriptor));
                        bridges.add(createBridge(target, declaration, name, descriptor));
                        replacement = new Handle(
                                Opcodes.H_INVOKESTATIC,
                                owner.name,
                                name,
                                descriptor,
                                (owner.access & Opcodes.ACC_INTERFACE) != 0
                        );
                        replacements.put(key, replacement);
                    }
                    dynamic.bsmArgs[i] = replacement;
                }
            }
        }
        owner.methods.addAll(bridges);
        if (!bridges.isEmpty()) {
            ScriptLambdaDeserializer.rebuild(owner);
        }
        return !bridges.isEmpty();
    }

    private static MethodNode createBridge(Handle target, String declaration, String name, String descriptor) {
        MethodNode bridge = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                descriptor,
                null,
                null
        );
        if (target.getTag() == Opcodes.H_NEWINVOKESPECIAL) {
            bridge.instructions.add(classInitialization(target.getOwner()));
        }
        Type bridgeType = Type.getMethodType(descriptor);
        int local = 0;
        for (Type argument : bridgeType.getArgumentTypes()) {
            bridge.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        bridge.instructions.add(new InvokeDynamicInsnNode(
                target.getTag() == Opcodes.H_NEWINVOKESPECIAL ? "newInstance" : target.getName(),
                descriptor,
                BOOTSTRAP,
                declaration.replace('/', '.'),
                target.getName(),
                target.getDesc(),
                handleOperation(target.getTag())
        ));
        bridge.instructions.add(new InsnNode(bridgeType.getReturnType().getOpcode(Opcodes.IRETURN)));
        return bridge;
    }

    private static int handleOperation(int tag) {
        return switch (tag) {
            case Opcodes.H_INVOKESTATIC -> ScriptAccessLinker.INVOKE_STATIC;
            case Opcodes.H_INVOKESPECIAL -> ScriptAccessLinker.INVOKE_SPECIAL;
            case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE -> ScriptAccessLinker.INVOKE_VIRTUAL;
            case Opcodes.H_NEWINVOKESPECIAL -> ScriptAccessLinker.NEW_INSTANCE;
            default -> throw new IllegalArgumentException("Unsupported method-handle tag " + tag);
        };
    }

    private static String handleCallDescriptor(Handle target) {
        return switch (target.getTag()) {
            case Opcodes.H_INVOKESTATIC -> target.getDesc();
            case Opcodes.H_INVOKESPECIAL, Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE ->
                    methodCallDescriptor(Opcodes.INVOKEVIRTUAL, target.getOwner(), target.getDesc());
            case Opcodes.H_NEWINVOKESPECIAL -> constructorCallDescriptor(target.getOwner(), target.getDesc());
            default -> throw new IllegalArgumentException("Unsupported method-handle tag " + target.getTag());
        };
    }

    private static TypeInsnNode popAllocation(ArrayDeque<TypeInsnNode> allocations, String owner) {
        // push() and forward iteration pair the most recent same-owner allocation first.
        for (java.util.Iterator<TypeInsnNode> iterator = allocations.iterator(); iterator.hasNext();) {
            TypeInsnNode allocation = iterator.next();
            if (allocation.desc.equals(owner)) {
                iterator.remove();
                return allocation;
            }
        }
        return null;
    }

    private static AbstractInsnNode nextExecutable(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getNext();
        while (current != null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        return current;
    }

    private static boolean shouldLink(String owner, Set<String> generatedOwners) {
        return !generatedOwners.contains(owner)
                && isApplicationOwner(owner);
    }

    private static boolean isApplicationOwner(String owner) {
        return !owner.startsWith("[")
                && !owner.startsWith("java/")
                && !owner.startsWith("javax/")
                && !owner.startsWith("jdk/")
                && !owner.startsWith("sun/")
                && !owner.equals(Type.getInternalName(ScriptProgram.class));
    }

    private static String linkedOwner(
            String owner,
            String name,
            String descriptor,
            boolean field,
            Set<String> generatedOwners,
            ScriptTypeResolver types
    ) {
        if (!isApplicationOwner(owner)) {
            return null;
        }
        String declaration = field ? types.fieldOwner(owner, name, descriptor) : types.methodOwner(owner, name, descriptor);
        return shouldLink(declaration, generatedOwners) ? declaration : null;
    }

    private static String fieldCallDescriptor(int opcode, String owner, String fieldDescriptor) {
        String receiver = 'L' + owner + ';';
        return switch (opcode) {
            case Opcodes.GETFIELD -> '(' + receiver + ')' + fieldDescriptor;
            case Opcodes.PUTFIELD -> '(' + receiver + fieldDescriptor + ")V";
            case Opcodes.GETSTATIC -> "()" + fieldDescriptor;
            case Opcodes.PUTSTATIC -> '(' + fieldDescriptor + ")V";
            default -> throw new IllegalArgumentException("Unknown field opcode " + opcode);
        };
    }

    private static String methodCallDescriptor(int opcode, String owner, String descriptor) {
        if (opcode == Opcodes.INVOKESTATIC) {
            return descriptor;
        }
        Type method = Type.getMethodType(descriptor);
        Type[] original = method.getArgumentTypes();
        Type[] linked = new Type[original.length + 1];
        linked[0] = Type.getObjectType(owner);
        System.arraycopy(original, 0, linked, 1, original.length);
        return Type.getMethodDescriptor(method.getReturnType(), linked);
    }

    private static String constructorCallDescriptor(String owner, String descriptor) {
        Type constructor = Type.getMethodType(descriptor);
        return Type.getMethodDescriptor(Type.getObjectType(owner), constructor.getArgumentTypes());
    }

    private static final class MethodTypeDescriptors {
        private static final String BOOTSTRAP = Type.getMethodDescriptor(
                Type.getType(java.lang.invoke.CallSite.class),
                Type.getType(java.lang.invoke.MethodHandles.Lookup.class),
                Type.getType(String.class),
                Type.getType(java.lang.invoke.MethodType.class),
                Type.getType(String.class),
                Type.getType(String.class),
                Type.getType(String.class),
                Type.INT_TYPE
        );

        private MethodTypeDescriptors() {
        }
    }

    static final class TransformationException extends RuntimeException {
        TransformationException(String message) {
            super(message);
        }

        TransformationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record BridgeKey(Handle target, String descriptor) { }
}
