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

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Replaces external member instructions with lazily linked privileged call sites. */
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

    static Map<String, byte[]> transform(Map<String, byte[]> classes) {
        Set<String> generatedOwners = classes.keySet().stream()
                .map(name -> name.replace('.', '/'))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, byte[]> transformed = new LinkedHashMap<>();
        classes.forEach((name, bytecode) -> transformed.put(name, transform(bytecode, generatedOwners)));
        return Map.copyOf(transformed);
    }

    private static byte[] transform(byte[] bytecode, Set<String> generatedOwners) {
        ClassReader reader = new ClassReader(bytecode);
        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, 0);
        boolean framesChanged = node.methods.stream()
                .map(method -> transformConstructors(method, generatedOwners))
                .reduce(false, Boolean::logicalOr);

        ClassWriter writer = new ClassWriter(framesChanged ? ClassWriter.COMPUTE_FRAMES : 0);
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
                        if (!shouldLink(owner, generatedOwners)) {
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
                                owner.replace('/', '.'),
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
                        if (opcode == Opcodes.INVOKESPECIAL
                                || !shouldLink(owner, generatedOwners)) {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            return;
                        }
                        int operation = opcode == Opcodes.INVOKESTATIC
                                ? ScriptAccessLinker.INVOKE_STATIC
                                : ScriptAccessLinker.INVOKE_VIRTUAL;
                        super.visitInvokeDynamicInsn(
                                name,
                                methodCallDescriptor(opcode, owner, descriptor),
                                BOOTSTRAP,
                                owner.replace('/', '.'),
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

    private static boolean transformConstructors(MethodNode method, Set<String> generatedOwners) {
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
            if (allocation == null || !shouldLink(invocation.owner, generatedOwners)) {
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
            method.instructions.remove(allocation);
            method.instructions.remove(duplicate);
            method.instructions.set(invocation, replacement);
            instruction = replacement;
            changed = true;
        }
        return changed;
    }

    private static TypeInsnNode popAllocation(ArrayDeque<TypeInsnNode> allocations, String owner) {
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
                && !owner.startsWith("java/")
                && !owner.startsWith("javax/")
                && !owner.startsWith("jdk/")
                && !owner.startsWith("sun/")
                && !owner.equals(Type.getInternalName(ScriptProgram.class));
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
}
