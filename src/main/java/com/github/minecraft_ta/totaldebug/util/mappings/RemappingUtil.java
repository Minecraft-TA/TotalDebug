package com.github.minecraft_ta.totaldebug.util.mappings;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

public class RemappingUtil {

    /**
     * obfuscated name -> pair of real name and class member mappings (obfuscated name -> real name)
     */
    private static final Map<String, Pair<String, Map<String, String>>> mcpMappings = new HashMap<>();
    private static final Map<String, String> forgeMappings = new HashMap<>();

    /**
     * Remaps all function, field and class references that are obfuscated in this class to their non-obfuscated names.
     *
     * @param clazz the class to read and remap
     * @return a {@link ClassWriter} which has the new class written to it; will be null if {@code methodConsumer} is
     * not null or something went wrong
     */
    @Nullable
    public static ClassWriter getRemappedClass(@Nonnull Class<?> clazz, @Nonnull RemappingContext context) {
        if (clazz.isInterface())
            return null;

        byte[] bytecode = ClassUtil.getBytecode(clazz);
        if (bytecode == null)
            return null;

        //read class into class node
        ClassWriter writer = context.write ? new ClassWriter(ClassWriter.COMPUTE_MAXS) : null;
        ClassReader reader = new ClassReader(bytecode);

        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        String codeSource = ClassUtil.getClassCodeSource(clazz);
        if (codeSource == null)
            return null;

        Pair<String, Map<String, String>> mappedPair = mcpMappings.get(codeSource.substring(0, codeSource.length() - 6));

        Map<String, String> attributeMappings = null;
        if (mappedPair != null) {
            attributeMappings = mappedPair.getRight();
        }

        //remap fields
        List<FieldNode> classFields = context.mapFields ? node.fields : null;
        for (int i = 0; classFields != null && i < classFields.size(); i++) {
            FieldNode field = classFields.get(i);
            if (mappedPair != null)
                field.name = attributeMappings.getOrDefault(field.name, field.name);
            else
                field.name = forgeMappings.getOrDefault(field.name, field.name);

            field.desc = remapTypeString(field.desc);

            if (field.signature != null) {
                int genericIndex = field.signature.indexOf('<');
                //lets not mess with this
                if (genericIndex == -1)
                    continue;

                field.signature = field.desc.substring(0, field.desc.length() - 1) +
                        remapTypeString(field.signature.substring(genericIndex));
            }
        }

        //remap methods
        List<MethodNode> classMethods = node.methods;
        for (int i = 0; classMethods != null && i < classMethods.size(); i++) {
            MethodNode method = classMethods.get(i);

            //remap method name and desc
            String signature = findMappedMemberName(method.name + method.desc, clazz).getRight();
            String newMethodName = signature.substring(0, signature.indexOf('('));
            if (newMethodName.equals(method.name))
                method.name = forgeMappings.getOrDefault(newMethodName, newMethodName);
            else
                method.name = newMethodName;
            method.desc = signature.substring(signature.indexOf('('));

            //remap local variables
            List<LocalVariableNode> localVariables = context.mapLocals ? method.localVariables : null;
            for (int j = 0; localVariables != null && j < localVariables.size(); j++) {
                LocalVariableNode localVariable = localVariables.get(j);
                localVariable.desc = remapTypeString(localVariable.desc);

                if (localVariable.signature != null) {
                    int genericIndex = localVariable.signature.indexOf('<');
                    //lets not mess with this
                    if (genericIndex == -1)
                        continue;

                    localVariable.signature = localVariable.desc.substring(0, localVariable.desc.length() - 1) +
                            remapTypeString(localVariable.signature.substring(genericIndex));
                }
            }

            InsnList instructions = method.instructions;
            if (instructions == null)
                continue;

            ListIterator<AbstractInsnNode> iterator = instructions.iterator();
            //remap instructions
            while (iterator.hasNext()) {
                AbstractInsnNode insnNode = iterator.next();

                if (context.mapMethodInsn && insnNode instanceof MethodInsnNode) { //method calls
                    MethodInsnNode methodInsnNode = (MethodInsnNode) insnNode;
                    Pair<String, Map<String, String>> pair = mcpMappings.get(methodInsnNode.owner);

                    String methodSignature = methodInsnNode.name + methodInsnNode.desc;
                    String newDesc = null;
                    String actualOwnerClass = methodInsnNode.owner;

                    //if owner is obfuscated
                    if (pair != null) {
                        String newSignature = pair.getRight().get(methodSignature);

                        if (newSignature != null)
                            newDesc = newSignature.substring(newSignature.indexOf('('));

                        //special case, need to also look in super classes for the method
                        if (methodInsnNode.getOpcode() == Opcodes.INVOKEINTERFACE ||
                                methodInsnNode.getOpcode() == Opcodes.INVOKEVIRTUAL ||
                                methodInsnNode.getOpcode() == Opcodes.INVOKESPECIAL) {
                            //get actual owner class
                            Class<?> currentClass = methodInsnNode.owner.equals(node.name) ?
                                    clazz :
                                    tryFindClassWithMappings(methodInsnNode.owner);
                            if (currentClass == null) {
                                TotalDebug.LOGGER.error("Owner of method or method instruction not found " + methodInsnNode.owner);
                                node.accept(writer);
                                return writer;
                            }

                            Pair<Class<?>, String> mappedMemberPair = findMappedMemberName(methodSignature, currentClass);
                            newSignature = mappedMemberPair.getRight();
                            actualOwnerClass = mappedMemberPair.getLeft().getName().replace('.', '/');
                        }

                        methodInsnNode.owner = pair.getLeft();

                        if (newSignature == null)
                            newSignature = methodSignature;
                        methodInsnNode.name = newSignature.substring(0, newSignature.indexOf('('));
                    } else {
                        //just try forge mappings if owner isn't obfuscated
                        methodInsnNode.name = forgeMappings.getOrDefault(methodInsnNode.name, methodInsnNode.name);
                    }

                    methodInsnNode.desc = newDesc == null ? remapTypeString(methodInsnNode.desc) : newDesc;

                    context.onMethodInsnMapping(method.name, actualOwnerClass + "." +
                            methodInsnNode.name + methodInsnNode.desc);
                } else if (context.mapMethodInsn && insnNode instanceof InvokeDynamicInsnNode) {
                    //TODO: maybe
                } else if (context.mapFieldInsn && insnNode instanceof FieldInsnNode) { //field access
                    FieldInsnNode fieldInsnNode = (FieldInsnNode) insnNode;
                    Pair<String, Map<String, String>> pair = mcpMappings.get(fieldInsnNode.owner);

                    String actualOwnerClass = fieldInsnNode.owner;

                    if (pair != null) {
                        fieldInsnNode.owner = pair.getLeft();

                        //get actual owner class
                        Class<?> currentClass = fieldInsnNode.owner.equals(node.name) ?
                                clazz :
                                tryFindClassWithMappings(fieldInsnNode.owner);
                        if (currentClass == null) {
                            TotalDebug.LOGGER.error("Owner of method or method instruction not found " + fieldInsnNode.owner);
                            node.accept(writer);
                            return writer;
                        }

                        Pair<Class<?>, String> mappedMemberPair = findMappedMemberName(fieldInsnNode.name, currentClass);
                        fieldInsnNode.name = mappedMemberPair.getRight();
                        actualOwnerClass = mappedMemberPair.getLeft().getName().replace('.', '/');
                    } else {
                        fieldInsnNode.name = forgeMappings.getOrDefault(fieldInsnNode.name, fieldInsnNode.name);
                    }

                    fieldInsnNode.desc = remapTypeString(fieldInsnNode.desc);

                    context.onFieldInsnMapping(method.name, actualOwnerClass + "." + fieldInsnNode.name);
                } else if (context.mapTypeAndLdcInsn && insnNode instanceof TypeInsnNode) { //type instruction
                    TypeInsnNode typeInsnNode = (TypeInsnNode) insnNode;
                    Pair<String, Map<String, String>> typePair = mcpMappings.get(typeInsnNode.desc);
                    if (typePair != null)
                        typeInsnNode.desc = typePair.getLeft();
                } else if (context.mapTypeAndLdcInsn && insnNode instanceof LdcInsnNode) { //constant pool instruction
                    LdcInsnNode ldcInsnNode = (LdcInsnNode) insnNode;
                    if (ldcInsnNode.cst instanceof Type) {
                        Type oldType = (Type) ldcInsnNode.cst;
                        ldcInsnNode.cst = Type.getType(remapTypeString(oldType.getDescriptor()));
                    }
                }
            }
        }

        if (!context.write)
            return null;

        if (mappedPair != null)
            node.name = mappedPair.getLeft();

        node.superName = mcpMappings.getOrDefault(node.superName, Pair.of(node.superName, null)).getLeft();

        for (int i = 0; i < node.interfaces.size(); i++) {
            String interfaceName = node.interfaces.get(i);
            node.interfaces.set(i, mcpMappings.getOrDefault(interfaceName, Pair.of(interfaceName, null)).getLeft());
        }

        node.accept(writer);
        return writer;
    }

    /**
     * Remaps all types in a string. The string has to be in the java bytecode format. The method searches for an
     * uppercase {@code L} followed by a {@code ;} at some point.
     * <br>
     * For example:
     * <br>
     * <code>
     * Lnet/minecraft/stuff;FFZZanythingLtest/test;(Lhi;Lk;IIII)V
     * </code>
     *
     * @param v the string to remap
     * @return the remapped string; will be the original string if nothing was remapped
     */
    @Nonnull
    public static String remapTypeString(@Nonnull String v) {
        StringBuilder builder = new StringBuilder(v);

        for (int i = 0; i < builder.length(); i++) {
            char c = builder.charAt(i);
            //search for non primitives
            if (c != 'L')
                continue;

            //remap from L until ;
            int end = builder.indexOf(";", i + 1);
            String name = builder.substring(i + 1, end);

            Pair<String, Map<String, String>> mappedPair = mcpMappings.get(name);
            if (mappedPair != null) {
                builder.replace(i + 1, end, mappedPair.getLeft());
                i += mappedPair.getLeft().length() - name.length();
            }
        }

        return builder.toString();
    }

    /**
     * Searches the given class and super classes for the given value. Can be a field name or method signature.
     *
     * @return the new mapped value if one was found; the original value otherwise
     */
    @Nonnull
    private static Pair<Class<?>, String> findMappedMemberName(String value, Class<?> clazz) {
        String newValue = null;
        Class<?> currentClass = clazz;

        while (true) {
            String codeSource = ClassUtil.getClassCodeSource(currentClass);
            if (codeSource == null)
                break;

            String className = codeSource.substring(codeSource.lastIndexOf('/') + 1, codeSource.length() - 6);

            Pair<String, Map<String, String>> currentNodePair = mcpMappings.get(className);

            if (currentNodePair != null) {
                newValue = currentNodePair.getRight().get(value);
                if (newValue != null)
                    break;
            }

            currentClass = currentClass.getSuperclass();

            if (currentClass == null)
                break;
        }

        return newValue == null ? Pair.of(clazz, value) : Pair.of(currentClass, newValue);
    }

    /**
     * Tries to find the given class using the given name. If it fails, it tries again by remapping the given name.
     *
     * @param superName the name of the class to find
     * @return the class instance corresponding to the given name; {@code null} otherwise
     */
    @Nullable
    public static Class<?> tryFindClassWithMappings(String superName) {
        Class<?> superClazz;
        try {
            superClazz = Class.forName(superName.replace("/", "."));
        } catch (Throwable e) {
            try {
                Pair<String, Map<String, String>> superClassPair = mcpMappings.get(superName);
                if (superClassPair == null)
                    return null;
                superClazz = Class.forName(superClassPair.getLeft().replace("/", "."));
            } catch (Throwable classNotFoundException) {
                TotalDebug.LOGGER.error("Unable to find class " + superName + ", " + mcpMappings.getOrDefault(superName, Pair.of("null", null)).getLeft(), e);
                return null;
            }
        }

        return superClazz;
    }

    /**
     * Loads all forge and searge mappings.
     */
    public static void loadMappings() {
        try {
            InputStream forgeMappingsStream = ClassUtil.class.getClassLoader().getResourceAsStream("forge_mappings.csv");
            InputStream mcpMappingsStream = ClassUtil.class.getClassLoader().getResourceAsStream("mcp_mappings.tsrg");

            if (forgeMappingsStream == null || mcpMappingsStream == null)
                throw new IllegalStateException("Forge or mcp mappings not found");

            IOUtils.readLines(forgeMappingsStream, StandardCharsets.UTF_8).stream()
                    .map(s -> s.split(","))
                    .forEach((ar) -> forgeMappings.put(ar[0], ar[1]));

            //load all mappings
            Map<String, String> currentMap = null;
            for (String line : IOUtils.readLines(mcpMappingsStream, StandardCharsets.UTF_8)) {
                int indexOfFirstSpace = line.indexOf(' ');

                if (line.startsWith("\t")) { //class field or method
                    if (currentMap == null)
                        throw new IllegalStateException("Hit unexpected \t while parsing mcp mappings");

                    String name = line.substring(1, indexOfFirstSpace);
                    String other = line.substring(indexOfFirstSpace + 1);

                    if (!other.startsWith("(")) { //field
                        currentMap.put(name, forgeMappings.getOrDefault(other, other));
                    } else { //method
                        int indexOfLastSpace = other.lastIndexOf(' ');
                        String desc = other.substring(0, indexOfLastSpace);

                        String newName = other.substring(indexOfLastSpace + 1);
                        newName = forgeMappings.getOrDefault(newName, newName);

                        currentMap.put(name + desc, newName + desc);
                    }
                } else { // start of new class
                    mcpMappings.put(line.substring(0, indexOfFirstSpace),
                            Pair.of(
                                    line.substring(indexOfFirstSpace + 1),
                                    (currentMap = new HashMap<>())
                            )
                    );
                }
            }

            //second pass to remap return values and parameters
            mcpMappings.forEach((obfuscatedClassName, pair) -> {
                Map<String, String> classMemberMap = pair.getRight();

                classMemberMap.forEach((k, v) -> {
                    int closingBracketIndex = v.lastIndexOf(')');
                    int openingBracketIndex = v.indexOf('(');

                    //not a method
                    if (openingBracketIndex == -1 || closingBracketIndex == -1)
                        return;

                    classMemberMap.put(k, v.substring(0, openingBracketIndex) + remapTypeString(v.substring(openingBracketIndex)));
                });
            });
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Error while loading mappings", e);
        }
    }

    public static class RemappingContext {
        protected boolean mapFields = true;
        protected boolean mapLocals = true;

        protected boolean mapMethodInsn = true;
        protected boolean mapFieldInsn = true;
        protected boolean mapTypeAndLdcInsn = true;

        protected boolean write = true;

        public void onMethodInsnMapping(@Nonnull String containedMethodName, @Nonnull String newMethodSignature) {
            //NO OP
        }

        public void onFieldInsnMapping(@Nonnull String containedMethodName, @Nonnull String newFieldSignature) {
            //NO OP
        }
    }
}
