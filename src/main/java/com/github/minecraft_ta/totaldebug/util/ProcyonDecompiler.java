package com.github.minecraft_ta.totaldebug.util;

import com.strobel.assembler.metadata.ITypeLoader;
import com.strobel.assembler.metadata.MetadataSystem;
import com.strobel.decompiler.AnsiTextOutput;
import com.strobel.decompiler.DecompilationOptions;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.languages.java.JavaFormattingOptions;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.common.asm.transformers.DeobfuscationTransformer;

import java.io.IOException;
import java.io.StringWriter;

public class ProcyonDecompiler {

    public static String decompile(String name) {
        ITypeLoader loader = (internalName, buffer) -> {
            if (internalName.endsWith(".class"))
                internalName = internalName.substring(0, internalName.length() - 6);

            try {
                byte[] code = ((LaunchClassLoader) ProcyonDecompiler.class.getClassLoader()).getClassBytes(internalName);
                if (code == null)
                    return false;

                code = new DeobfuscationTransformer().transform(internalName, internalName, code);

                buffer.position(0);
                buffer.putByteArray(code, 0, code.length);
                buffer.position(0);
                return true;
            } catch (IOException e) {
                return false;
            }
        };

        DecompilerSettings settings = new DecompilerSettings();
        settings.setForceExplicitImports(true);
        settings.setUnicodeOutputEnabled(false);
        settings.setShowSyntheticMembers(false);
        settings.setRetainRedundantCasts(false);
        settings.setForceExplicitTypeArguments(true);
        settings.setTypeLoader(loader);
        settings.setJavaFormattingOptions(JavaFormattingOptions.createDefault());

        MetadataSystem system = new MetadataSystem(loader);
        system.setEagerMethodLoadingEnabled(true);

        DecompilationOptions decompilationOptions = new DecompilationOptions();
        decompilationOptions.setSettings(settings);
        decompilationOptions.setFullDecompilation(true);

        StringWriter writer = new StringWriter();
        //Why would Procyon even check for this...
        System.setProperty("Ansi", "true");
        settings.getLanguage().decompileType(system.lookupType(name).resolve(), new AnsiTextOutput(writer, AnsiTextOutput.ColorScheme.LIGHT), decompilationOptions);
        return writer.toString();
    }
}
