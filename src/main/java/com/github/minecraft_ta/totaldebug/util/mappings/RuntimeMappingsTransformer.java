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

    public static final Map<String, String> FORGE_MAPPINGS = new HashMap<>();
    public static final Map<String, String> FORGE_MAPPINGS_INVERSE = new HashMap<>();

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
        return mapName(name);
    }

    @Override
    public String mapMethodName(String owner, String name, String desc) {
        return mapName(name);
    }

    private String mapName(String key) {
        return !this.reobfuscate ? FORGE_MAPPINGS.getOrDefault(key, key) : FORGE_MAPPINGS_INVERSE.getOrDefault(key, key);
    }

    /**
     * Loads all forge and searge mappings.
     */
    public static void loadMappings() {
        try {
            InputStream forgeMappingsStream = ClassUtil.class.getClassLoader().getResourceAsStream("forge_mappings.csv");

            if (forgeMappingsStream == null)
                throw new IllegalStateException("Forge or mcp mappings not found");

            //func_123 -> aFunction, field_234 -> aField
            IOUtils.readLines(forgeMappingsStream, StandardCharsets.UTF_8).stream()
                    .map(s -> s.split(","))
                    .forEach((s) -> {
                        FORGE_MAPPINGS.put(s[0], s[1]);
                        FORGE_MAPPINGS_INVERSE.put(s[1], s[0]);
                    });
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Error while loading mappings", e);
        }
    }
}
