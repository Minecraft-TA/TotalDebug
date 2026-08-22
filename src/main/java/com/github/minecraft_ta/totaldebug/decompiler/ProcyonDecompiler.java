package com.github.minecraft_ta.totaldebug.decompiler;

import com.github.minecraft_ta.totaldebug.bytecode.ClassBytecodeSource;
import com.strobel.assembler.metadata.ITypeLoader;
import com.strobel.assembler.metadata.MetadataSystem;
import com.strobel.assembler.metadata.TypeDefinition;
import com.strobel.assembler.metadata.TypeReference;
import com.strobel.decompiler.DecompilationOptions;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;
import com.strobel.decompiler.languages.java.JavaFormattingOptions;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Objects;

public final class ProcyonDecompiler {
    private ProcyonDecompiler() {
    }

    public static String decompile(Class<?> targetClass, ClassBytecodeSource bytecodeSource) {
        Objects.requireNonNull(targetClass, "targetClass");
        Objects.requireNonNull(bytecodeSource, "bytecodeSource");

        ITypeLoader typeLoader = (internalName, buffer) -> {
            try {
                byte[] classBytes = bytecodeSource.findClassBytes(internalName);
                if (classBytes == null) {
                    return false;
                }

                buffer.reset(classBytes.length);
                buffer.putByteArray(classBytes, 0, classBytes.length);
                buffer.position(0);
                return true;
            } catch (IOException exception) {
                return false;
            }
        };

        DecompilerSettings settings = new DecompilerSettings();
        settings.setForceExplicitImports(true);
        settings.setUnicodeOutputEnabled(false);
        settings.setShowSyntheticMembers(false);
        settings.setRetainRedundantCasts(false);
        settings.setForceExplicitTypeArguments(true);
        settings.setTypeLoader(typeLoader);
        settings.setJavaFormattingOptions(JavaFormattingOptions.createDefault());

        MetadataSystem metadataSystem = new MetadataSystem(typeLoader);
        metadataSystem.setEagerMethodLoadingEnabled(true);
        TypeReference typeReference = metadataSystem.lookupType(targetClass.getName().replace('.', '/'));
        TypeDefinition typeDefinition = typeReference == null ? null : typeReference.resolve();
        if (typeDefinition == null) {
            throw new IllegalArgumentException("Unable to load bytecode for " + targetClass.getName());
        }

        DecompilationOptions options = new DecompilationOptions();
        options.setSettings(settings);
        options.setFullDecompilation(true);

        StringWriter writer = new StringWriter();
        settings.getLanguage().decompileType(typeDefinition, new PlainTextOutput(writer), options);
        return writer.toString();
    }
}
