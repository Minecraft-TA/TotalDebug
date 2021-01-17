package com.github.minecraft_ta.totaldebug.util;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;

public class BytecodeUtil {

    /**
     * obfuscated name -> pair of real name and class member mappings (obfuscated name -> real name)
     */
    private static final Map<String, Pair<String, Map<String, String>>> mcpMappings = new HashMap<>();
    private static final Map<String, String> forgeMappings = new HashMap<>();

    private BytecodeUtil() {
    }

    public static void loadMappings() {
        try {
            InputStream forgeMappingsStream = BytecodeUtil.class.getClassLoader().getResourceAsStream("forge_mappings.csv");
            InputStream mcpMappingsStream = BytecodeUtil.class.getClassLoader().getResourceAsStream("mcp_mappings.tsrg");

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

                    classMemberMap.put(k, v.substring(0, openingBracketIndex) + remapSignatureString(v.substring(openingBracketIndex)));
                });
            });
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Error while loading mappings", e);
        }
    }

    @Nullable
    public static byte[] getBytecode(Class<?> clazz) {
        String codeSource = getClassCodeSource(clazz);
        if (codeSource == null)
            return null;

        try (InputStream inputStream = clazz.getClassLoader().getResourceAsStream(codeSource)) {
            if (inputStream == null)
                throw new RuntimeException("Class " + clazz.getName() + " not found");

            byte[] buffer = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int r;
            while ((r = inputStream.read(buffer, 0, buffer.length)) != -1) {
                out.write(buffer, 0, r);
            }

            return out.toByteArray();
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to close stream " + clazz.getName(), e);
            return null;
        }
    }

    @Nullable
    private static String getClassCodeSource(Class<?> clazz) {
        ProtectionDomain protectionDomain = clazz.getProtectionDomain();
        if (protectionDomain == null)
            return null;

        CodeSource codeSourceObj = protectionDomain.getCodeSource();
        if (codeSourceObj == null)
            return null;

        String codeSource = codeSourceObj.getLocation().toString();
        if (codeSource.startsWith("jar"))
            codeSource = codeSource.substring(codeSource.lastIndexOf('!') + 2);
        else
            codeSource = clazz.getName().replace(".", "/") + ".class";
        return codeSource;
    }

    public static String remapSignatureString(String v) {
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

    @Nullable
    public static ClassWriter getRemappedClass(@Nonnull Class<?> clazz) {

        //TODO: clean up this mess


        byte[] bytecode = getBytecode(clazz);
        if (bytecode == null)
            return null;

        //read class into class node
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        ClassReader reader = new ClassReader(bytecode);

        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        //init super class name cache
        Map<String, Deque<String>> superClassCache = new HashMap<>();
        superClassCache.put(node.name, new LinkedList<>(Collections.singletonList(node.name)));

        String codeSource = getClassCodeSource(clazz);
        if (codeSource == null)
            return null;

        Pair<String, Map<String, String>> mappedPair = mcpMappings.get(codeSource.substring(0, codeSource.length() - 6));

        Map<String, String> attributeMappings = null;
        if (mappedPair != null) {
            attributeMappings = mappedPair.getRight();
        }

        //remap fields
        for (FieldNode field : node.fields) {
            if (mappedPair != null)
                field.name = attributeMappings.getOrDefault(field.name, field.name);
            else
                field.name = forgeMappings.getOrDefault(field.name, field.name);

            field.desc = remapSignatureString(field.desc);

            if (field.signature != null) {
                field.signature = field.desc.substring(0, field.desc.length() - 1) +
                        remapSignatureString(field.signature.substring(field.signature.indexOf('<')));
            }
        }

        //remap methods
        for (MethodNode method : node.methods) {
            InsnList instructions = method.instructions;
            ListIterator<AbstractInsnNode> iterator = instructions.iterator();

            //remap method name and desc
            String signature = findMappedMemberName(method.name + method.desc, superClassCache.get(node.name));
            method.name = signature.substring(0, signature.indexOf('('));
            method.desc = signature.substring(signature.indexOf('('));

            //remap local variables
            for (LocalVariableNode localVariable : method.localVariables) {
                localVariable.desc = remapSignatureString(localVariable.desc);

                if (localVariable.signature != null) {
                    localVariable.signature = localVariable.desc.substring(0, localVariable.desc.length() - 1) +
                            remapSignatureString(localVariable.signature.substring(localVariable.signature.indexOf('<')));
                }
            }

            //remap instructions
            while (iterator.hasNext()) {
                AbstractInsnNode insnNode = iterator.next();

                if (insnNode instanceof MethodInsnNode) { //method calls
                    MethodInsnNode methodInsnNode = (MethodInsnNode) insnNode;
                    Pair<String, Map<String, String>> pair = mcpMappings.get(methodInsnNode.owner);

                    String methodSignature = methodInsnNode.name + methodInsnNode.desc;
                    String newDesc = null;

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
                            ClassNode currentNode = loadClassNodeIfNotEqual(methodInsnNode.owner, node);
                            if (currentNode == null) {
                                TotalDebug.LOGGER.error("Owner of method or method instruction not found " + methodInsnNode.owner);
                                node.accept(writer);
                                return writer;
                            }

                            newSignature = findMappedMemberName(methodSignature, superClassCache.computeIfAbsent(currentNode.name, (k) -> new LinkedList<>(Collections.singletonList(k))));
                        }

                        methodInsnNode.owner = pair.getLeft();

                        if (newSignature == null)
                            newSignature = methodSignature;
                        methodInsnNode.name = newSignature.substring(0, newSignature.indexOf('('));
                    } else {
                        //just try forge mappings if owner isn't obfuscated
                        methodInsnNode.name = forgeMappings.getOrDefault(methodInsnNode.name, methodInsnNode.name);
                    }

                    methodInsnNode.desc = newDesc == null ? remapSignatureString(methodInsnNode.desc) : newDesc;
                } else if (insnNode instanceof InvokeDynamicInsnNode) {
                    //TODO: maybe
                } else if (insnNode instanceof FieldInsnNode) { //field access
                    FieldInsnNode fieldInsnNode = (FieldInsnNode) insnNode;
                    Pair<String, Map<String, String>> pair = mcpMappings.get(fieldInsnNode.owner);
                    if (pair != null) {
                        fieldInsnNode.owner = pair.getLeft();

                        //get actual owner class
                        ClassNode currentNode = loadClassNodeIfNotEqual(fieldInsnNode.owner, node);
                        if (currentNode == null) {
                            TotalDebug.LOGGER.error("Owner of method or method instruction not found " + fieldInsnNode.owner);
                            node.accept(writer);
                            return writer;
                        }

                        fieldInsnNode.name = findMappedMemberName(fieldInsnNode.name, superClassCache.computeIfAbsent(currentNode.name, (k) -> new LinkedList<>(Collections.singletonList(k))));
                    } else {
                        fieldInsnNode.name = forgeMappings.getOrDefault(fieldInsnNode.name, fieldInsnNode.name);
                    }

                    fieldInsnNode.desc = remapSignatureString(fieldInsnNode.desc);
                } else if (insnNode instanceof TypeInsnNode) { //type instruction
                    TypeInsnNode typeInsnNode = (TypeInsnNode) insnNode;
                    Pair<String, Map<String, String>> typePair = mcpMappings.get(typeInsnNode.desc);
                    if (typePair != null)
                        typeInsnNode.desc = typePair.getLeft();
                } else if (insnNode instanceof LdcInsnNode) { //constant pool instruction
                    LdcInsnNode ldcInsnNode = (LdcInsnNode) insnNode;
                    if (ldcInsnNode.cst instanceof Type) {
                        Type oldType = (Type) ldcInsnNode.cst;
                        ldcInsnNode.cst = Type.getType(remapSignatureString(oldType.getDescriptor()));
                    }
                }
            }
        }

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

    @Nullable
    private static ClassNode loadClassNodeIfNotEqual(String toLoad, ClassNode current) {
        if (toLoad.equals(current.name)) {
            return current;
        } else {
            ClassNode currentNode = new ClassNode();

            Class<?> ownerClass = tryFindClassWithMappings(toLoad);
            if (ownerClass == null) {
                return null;
            }

            byte[] ownerClassBytecode = getBytecode(ownerClass);
            if (ownerClassBytecode == null) {
                return null;
            }

            ClassReader superReader = new ClassReader(ownerClassBytecode);
            superReader.accept(currentNode, 0);

            return currentNode;
        }
    }

    /**
     * Searches the given class and super classes for the given value. Can be a field name or method signature.
     * Automatically expands the super class name cache if not all super classes have been discovered.
     *
     * @return the new mapped value if one was found; the original value otherwise
     */
    @Nonnull
    private static String findMappedMemberName(String value, Deque<String> classes) {
        String newValue = null;

        for (String className : classes) {
            Pair<String, Map<String, String>> currentNodePair = mcpMappings.get(className);

            if (currentNodePair != null) {
                newValue = currentNodePair.getRight().get(value);
                if (newValue != null)
                    break;
            }
        }

        if (newValue != null) {
            return newValue;
        }

        while (true) {
            String superName = classes.getLast();

            if (superName.isEmpty())
                break;

            Class<?> superClazz = tryFindClassWithMappings(superName);
            if (superClazz == null)
                break;

            byte[] superClassBytecode = getBytecode(superClazz);
            if (superClassBytecode == null) {
                //last class in list is invalid
                classes.removeLast();
                classes.addLast("");
                break;
            }

            //TODO: this is extremely fucking retarded -> reflection exists
            ClassReader superReader = new ClassReader(superClassBytecode);
            ClassNode currentNode = new ClassNode();
            superReader.accept(currentNode, 0);

            superName = currentNode.superName;

            if (superName == null) {
                classes.addLast("");
                break;
            }

            classes.addLast(currentNode.superName);

            Pair<String, Map<String, String>> currentNodePair = mcpMappings.get(superName);

            if (currentNodePair != null) {
                newValue = currentNodePair.getRight().get(value);
                if (newValue != null)
                    break;
            }
        }

        return newValue == null ? value : newValue;
    }

    @Nullable
    private static Class<?> tryFindClassWithMappings(String superName) {
        Class<?> superClazz;
        try {
            superClazz = Class.forName(superName.replace("/", "."));
        } catch (ClassNotFoundException e) {
            try {
                Pair<String, Map<String, String>> superClassPair = mcpMappings.get(superName);
                if (superClassPair == null)
                    return null;
                superClazz = Class.forName(superClassPair.getLeft().replace("/", "."));
            } catch (ClassNotFoundException classNotFoundException) {
                TotalDebug.LOGGER.error("Unable to find class " + superName + ", " + mcpMappings.getOrDefault(superName, Pair.of("null", null)).getLeft(), e);
                return null;
            }
        }
        return superClazz;
    }
}
