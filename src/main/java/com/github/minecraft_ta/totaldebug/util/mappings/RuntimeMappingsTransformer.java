package com.github.minecraft_ta.totaldebug.util.mappings;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class RuntimeMappingsTransformer extends Remapper implements IClassTransformer {

    /**
     * obfuscated name -> pair of real name and class member mappings (obfuscated name -> real name)
     */
    private static final Map<String, BiMap<String, String>> MCP_MAPPINGS = new HashMap<>();

    public static final Map<String, String> FORGE_MAPPINGS = new HashMap<>();

    private final boolean reobfuscate;

    public RuntimeMappingsTransformer() {
        this(false);
    }

    public RuntimeMappingsTransformer(boolean reobfuscate) {
        this.reobfuscate = reobfuscate;
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        ClassReader reader = new ClassReader(basicClass);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        ClassVisitor classVisitor = new ClassRemapper(writer, this) {
            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                //This fixes inner class names, for some reason `name` is de-obfuscated but `innerName` is not
                int dollarIndex = name.lastIndexOf('$');
                if (innerName != null && dollarIndex != -1)
                    innerName = name.substring(dollarIndex + 1);
                super.visitInnerClass(name, outerName, innerName, access);
            }
        };
        //Skip debug symbols for minecraft classes to allow Procyon to generate proper variable names
        reader.accept(classVisitor, transformedName.startsWith("net.minecraft") ? ClassReader.SKIP_DEBUG : 0);
        return writer.toByteArray();
    }

    @Override
    public String mapFieldName(String owner, String name, String desc) {
        return findMappedMember(owner, name);
    }

    @Override
    public String mapMethodName(String owner, String name, String desc) {
        return StringUtils.substringBefore(findMappedMember(owner, name + desc), "(");
    }

    private String findMappedMember(String owner, String name) {
        BiMap<String, String> memberMap = MCP_MAPPINGS.get(owner);
        String newName = memberMap == null ? null : getFromBiMap(name, memberMap, this.reobfuscate);
        //Don't continue if it's not a forge obfuscated field/method, or we found a mapping
        if (newName != null || (!this.reobfuscate && !name.startsWith("field") && !name.startsWith("func")))
            return newName == null ? name : newName;

        //Find mapped name in super classes
        try {
            Class<?> ownerClass = Class.forName(owner.replace('/', '.'), false, RuntimeMappingsTransformer.class.getClassLoader());

            while (ownerClass != null) {
                memberMap = MCP_MAPPINGS.get(ownerClass.getName().replace('.', '/'));
                newName = memberMap == null ? null : getFromBiMap(name, memberMap, this.reobfuscate);

                //Search super interfaces for interfaces
                if (newName == null)
                    newName = findMappedMemberOfInterface(ownerClass, name);

                if (newName != null)
                    return newName;

                ownerClass = ownerClass.getSuperclass();
            }
        } catch (Throwable e) {
            return name;
        }

        return name;
    }

    private String findMappedMemberOfInterface(Class<?> interfaceClass, String name) {
        for (Class<?> interfaceSubClass : interfaceClass.getInterfaces()) {
            BiMap<String, String> memberMap = MCP_MAPPINGS.get(interfaceSubClass.getName().replace('.', '/'));
            String newName = memberMap == null ? findMappedMemberOfInterface(interfaceSubClass, name) : getFromBiMap(name, memberMap, this.reobfuscate);
            if (newName != null)
                return newName;
        }

        return null;
    }

    private <T> T getFromBiMap(T key, BiMap<T, T> biMap, boolean inverse) {
        return inverse ? biMap.inverse().get(key) : biMap.get(key);
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

            //func_123 -> aFunction, field_234 -> a field
            IOUtils.readLines(forgeMappingsStream, StandardCharsets.UTF_8).stream()
                    .map(s -> s.split(","))
                    .forEach((s) -> FORGE_MAPPINGS.put(s[0], s[1]));

            //aym$a -> net/minecraft/block/BlockSand$EnumType
            Map<String, String> typeNameMap = new HashMap<>();

            //a -> VARIANT, VARIANT -> a, field_123 -> aField, aField -> field_123
            BiMap<String, String> currentMap = null;
            for (String line : IOUtils.readLines(mcpMappingsStream, StandardCharsets.UTF_8)) {
                int indexOfFirstSpace = line.indexOf(' ');

                if (line.startsWith("\t")) { //class field or method
                    if (currentMap == null)
                        throw new IllegalStateException("Hit unexpected \t while parsing mcp mappings");

                    String other = line.substring(indexOfFirstSpace + 1);

                    if (!other.startsWith("(")) { //field
                        currentMap.put(other, FORGE_MAPPINGS.getOrDefault(other, other));
                    } else { //method
                        int indexOfLastSpace = other.lastIndexOf(' ');
                        String name = other.substring(indexOfLastSpace + 1);
                        String desc = other.substring(0, indexOfLastSpace);

                        currentMap.put(name + desc, FORGE_MAPPINGS.getOrDefault(name, name) + desc);
                    }
                } else { // start of new class
                    String newName = line.substring(indexOfFirstSpace + 1);
                    typeNameMap.put(line.substring(0, indexOfFirstSpace), newName);
                    MCP_MAPPINGS.put(newName, (currentMap = HashBiMap.create()));
                }
            }

            //second pass to remap method descriptors
            MCP_MAPPINGS.forEach((className, memberMap) -> {
                BiMap<String, String> newMap = HashBiMap.create();
                memberMap.forEach((k, v) -> {
                    //not a method
                    if (v.indexOf('(') == -1) {
                        newMap.put(k, v);
                        return;
                    }

                    newMap.put(remapTypeString(k, typeNameMap), remapTypeString(v, typeNameMap));
                });

                memberMap.clear();
                memberMap.putAll(newMap);
            });
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Error while loading mappings", e);
        }
    }

    /**
     * Remaps all types in a string. The string has to be in the java bytecode type format. The method searches for an
     * uppercase {@code L} followed by a {@code ;} at some point.
     * <br>
     * For example:
     * <br>
     * <code>
     * Lnet/minecraft/stuff;FFZZanythingLtest/test;(Lhi;Lk;IIII)V
     * </code>
     *
     * @param value the string to remap
     * @return the remapped string; will be the original string if nothing was remapped
     */
    @Nonnull
    public static String remapTypeString(@Nonnull String value, Map<String, String> typeMappings) {
        StringBuilder builder = new StringBuilder(value);

        for (int i = 0; i < builder.length(); i++) {
            char c = builder.charAt(i);
            //search for non primitives
            if (c != 'L')
                continue;

            //remap from L until ;
            int end = builder.indexOf(";", i + 1);
            if (end == -1)
                break;
            String name = builder.substring(i + 1, end);

            String mapping = typeMappings.get(name);
            if (mapping != null) {
                builder.replace(i + 1, end, mapping);
                i += mapping.length() - name.length();
            }
        }

        return builder.toString();
    }
}
