package com.github.minecraft_ta.totaldebug.util.mappings;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.util.mappings.asm6.ClassRemapper;
import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.commons.io.IOUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class RuntimeMappingsTransformer extends Remapper implements IClassTransformer {

    /**
     * Class name -> (getCount(I)V -> func_35_a)
     */
    private static final Map<String, Map<String, String>> OBFUSCATION_MAPPINGS = new HashMap<>();

    public static final Map<String, String> FORGE_MAPPINGS = new HashMap<>();

    private final boolean obfuscate;

    public RuntimeMappingsTransformer() {
        this(false);
    }

    public RuntimeMappingsTransformer(boolean obfuscate) {
        this.obfuscate = obfuscate;
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

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                // Remove empty signatures
                return super.visitField(access, name, desc, signature != null && signature.isEmpty() ? null : signature, value);
            }
        };
        //Skip debug symbols for minecraft classes to allow Procyon to generate proper variable names
        reader.accept(classVisitor, transformedName.startsWith("net.minecraft") ? ClassReader.SKIP_DEBUG : 0);
        return writer.toByteArray();
    }

    @Override
    public String mapFieldName(String owner, String name, String desc) {
        return mapName(owner, name, "");
    }

    @Override
    public String mapMethodName(String owner, String name, String desc) {
        return mapName(owner, name, desc);
    }

    private String mapName(String owner, String name, String desc) {
        return !this.obfuscate ? FORGE_MAPPINGS.getOrDefault(name, name) : obfuscateMember(owner, name, desc);
    }

    private String obfuscateMember(String owner, String name, String desc) {
        String nameAndDesc = name + desc;

        Map<String, String> memberMap = OBFUSCATION_MAPPINGS.get(owner);
        String newName = memberMap == null ? null : memberMap.get(nameAndDesc);
        // Don't continue if we found a mapping
        if (newName != null)
            return newName;

        // Find mapped name in super classes
        try {
            Class<?> ownerClass = Class.forName(owner.replace('/', '.'), false, RuntimeMappingsTransformer.class.getClassLoader());

            while (ownerClass != null) {
                memberMap = OBFUSCATION_MAPPINGS.get(ownerClass.getName().replace('.', '/'));
                newName = memberMap == null ? null : memberMap.get(nameAndDesc);

                // Search in interfaces if we're looking for a method
                if (newName == null && !desc.isEmpty())
                    newName = findMappedMemberOfInterface(ownerClass, nameAndDesc);

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
            Map<String, String> memberMap = OBFUSCATION_MAPPINGS.get(interfaceSubClass.getName().replace('.', '/'));
            String newName;
            if ((memberMap != null && (newName = memberMap.get(name)) != null) ||
                (newName = findMappedMemberOfInterface(interfaceSubClass, name)) != null
            ) {
                return newName;
            }
        }

        return null;
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

            //func_123 -> aFunction, field_234 -> aField
            IOUtils.readLines(forgeMappingsStream, StandardCharsets.UTF_8).stream()
                    .map(s -> s.split(","))
                    .forEach((s) -> {
                        FORGE_MAPPINGS.put(s[0], s[1]);
                    });

            for (String line : IOUtils.readLines(mcpMappingsStream, StandardCharsets.UTF_8)) {
                // Remove `{CL, MD, FD}: ` from the start of the line
                String[] info = line.substring(4).split(" ");

                if (line.startsWith("F")) {
                    String[] names = splitAfterLastSlash(info[1]);
                    OBFUSCATION_MAPPINGS
                            .computeIfAbsent(names[0], (k) -> new HashMap<>())
                            .put(FORGE_MAPPINGS.getOrDefault(names[1], names[1]), names[1]);
                } else if (line.startsWith("M")) {
                    String[] names = splitAfterLastSlash(info[2]);
                    OBFUSCATION_MAPPINGS
                            .computeIfAbsent(names[0], (k) -> new HashMap<>())
                            .put(FORGE_MAPPINGS.getOrDefault(names[1], names[1]) + info[3], names[1]);
                }
            }
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Error while loading mappings", e);
        }
    }

    private static String[] splitAfterLastSlash(String s) {
        int index = s.lastIndexOf('/');
        return new String[]{s.substring(0, index), s.substring(index + 1)};
    }
}
