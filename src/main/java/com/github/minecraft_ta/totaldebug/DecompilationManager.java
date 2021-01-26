package com.github.minecraft_ta.totaldebug;

import com.github.minecraft_ta.totaldebug.gui.codeviewer.CodeViewScreen;
import com.github.minecraft_ta.totaldebug.util.ProcyonDecompiler;
import com.google.common.base.Charsets;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.client.FMLClientHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class DecompilationManager {

    private Path dataDir;

    public CompletableFuture<String> getDecompiledFileContent(Class<?> clazz) {
        return CompletableFuture.supplyAsync(() -> {
            Path decompiledFilePath = dataDir.resolve(clazz.getName() + ".java");

            //if not yet decompiled
            if (!Files.exists(decompiledFilePath)) {
                if (!decompileClass(clazz))
                    return "";
            }

            try {
                return new String(Files.readAllBytes(decompiledFilePath), Charsets.UTF_8);
            } catch (IOException e) {
                TotalDebug.LOGGER.error("Error while reading decompiled file!", e);
                return "";
            }
        });
    }

    public boolean decompileClass(Class<?> clazz) {
        String name = clazz.getName();

        Path output = this.dataDir.resolve(name + ".java");
        try {
            Files.write(output, ProcyonDecompiler.decompile(name).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to delete or write java file " + name, e);
        }

        return true;
    }

    public void setup() {
        this.dataDir = FMLClientHandler.instance().getSavesDirectory().toPath().getParent().resolve("code-viewer");

        try {
            if (Files.notExists(dataDir))
                Files.createDirectory(dataDir);
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to create cache directory!", e);
        }
    }

    public void openGui(Class<?> clazz) {
        getDecompiledFileContent(clazz).exceptionally(throwable -> {
            throwable.printStackTrace();
            return "";
        }).thenAccept(s -> {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                CodeViewScreen screen = new CodeViewScreen();
                FMLClientHandler.instance().showGuiScreen(screen);
                screen.setJavaCode(s);
            });
        });
    }
}
