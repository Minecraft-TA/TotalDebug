package com.github.minecraft_ta.totaldebug.evaluation;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static java.lang.invoke.LambdaMetafactory.FLAG_SERIALIZABLE;

/** Keeps javac's serializable-lambda reconstruction in sync with rewritten implementation handles. */
final class ScriptLambdaDeserializer {
    private static final String METHOD = "$deserializeLambda$";
    private static final String SERIALIZED_LAMBDA = "java/lang/invoke/SerializedLambda";
    private static final String DESCRIPTOR = "(Ljava/lang/invoke/SerializedLambda;)Ljava/lang/Object;";

    private ScriptLambdaDeserializer() {
    }

    static void rebuild(ClassNode owner) {
        List<InvokeDynamicInsnNode> factories = new ArrayList<>();
        for (MethodNode method : owner.methods) {
            if (isDeserializer(method)) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof InvokeDynamicInsnNode factory
                        && factory.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                        && factory.bsm.getName().equals("altMetafactory")
                        && factory.bsmArgs.length >= 4
                        && factory.bsmArgs[3] instanceof Integer flags && (flags & FLAG_SERIALIZABLE) != 0) {
                    factories.add(factory);
                }
            }
        }
        if (factories.isEmpty()) {
            return;
        }
        MethodNode method = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                METHOD, DESCRIPTOR, null, null);
        for (InvokeDynamicInsnNode factory : factories) {
            Handle implementation = (Handle) factory.bsmArgs[1];
            Type[] captures = Type.getArgumentTypes(factory.desc);
            LabelNode next = new LabelNode();
            requireString(method, "getImplClass", implementation.getOwner(), next);
            requireString(method, "getImplMethodName", implementation.getName(), next);
            requireString(method, "getImplMethodSignature", implementation.getDesc(), next);
            requireInt(method, "getImplMethodKind", implementation.getTag(), next);
            requireString(method, "getFunctionalInterfaceClass", Type.getReturnType(factory.desc).getInternalName(), next);
            requireString(method, "getFunctionalInterfaceMethodName", factory.name, next);
            requireString(method, "getFunctionalInterfaceMethodSignature", ((Type) factory.bsmArgs[0]).getDescriptor(), next);
            requireString(method, "getInstantiatedMethodType", ((Type) factory.bsmArgs[2]).getDescriptor(), next);
            requireInt(method, "getCapturedArgCount", captures.length, next);
            for (int i = 0; i < captures.length; i++) {
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.add(new LdcInsnNode(i));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SERIALIZED_LAMBDA,
                        "getCapturedArg", "(I)Ljava/lang/Object;", false));
                castCapture(method, captures[i]);
            }
            method.instructions.add(new InvokeDynamicInsnNode(factory.name, factory.desc, factory.bsm, factory.bsmArgs.clone()));
            method.instructions.add(new InsnNode(Opcodes.ARETURN));
            method.instructions.add(next);
        }
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalArgumentException"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new LdcInsnNode("Invalid lambda deserialization"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException",
                "<init>", "(Ljava/lang/String;)V", false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        owner.methods.removeIf(ScriptLambdaDeserializer::isDeserializer);
        owner.methods.add(method);
    }

    private static boolean isDeserializer(MethodNode method) {
        return method.name.equals(METHOD) && method.desc.equals(DESCRIPTOR);
    }

    private static void requireString(MethodNode method, String getter, String expected, LabelNode next) {
        method.instructions.add(new LdcInsnNode(expected));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SERIALIZED_LAMBDA,
                getter, "()Ljava/lang/String;", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                "equals", "(Ljava/lang/Object;)Z", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, next));
    }

    private static void requireInt(MethodNode method, String getter, int expected, LabelNode next) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SERIALIZED_LAMBDA, getter, "()I", false));
        method.instructions.add(new LdcInsnNode(expected));
        method.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPNE, next));
    }

    private static void castCapture(MethodNode method, Type type) {
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
            method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
            return;
        }
        String wrapper = switch (type.getSort()) {
            case Type.BOOLEAN -> "Boolean";
            case Type.BYTE -> "Byte";
            case Type.CHAR -> "Character";
            case Type.SHORT -> "Short";
            case Type.INT -> "Integer";
            case Type.LONG -> "Long";
            case Type.FLOAT -> "Float";
            case Type.DOUBLE -> "Double";
            default -> throw new IllegalArgumentException("Invalid lambda capture type " + type);
        };
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/" + wrapper));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/" + wrapper,
                type.getClassName() + "Value", "()" + type.getDescriptor(), false));
    }
}
