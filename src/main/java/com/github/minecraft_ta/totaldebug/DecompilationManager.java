package com.github.minecraft_ta.totaldebug;

import com.github.minecraft_ta.totaldebug.gui.codeviewer.CodeViewScreen;
import com.github.minecraft_ta.totaldebug.codeViewer.CompanionApp;
import com.github.minecraft_ta.totaldebug.util.ProcyonDecompiler;
import com.google.common.base.Charsets;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.client.FMLClientHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class DecompilationManager {

    public static final String DECOMPILED_FILES_FOLDER = "decompiled-files";

    private Path dataDir;
    private Path decompilationDir;

    public CompletableFuture<String> getDecompiledFileContent(Class<?> clazz) {
        return CompletableFuture.supplyAsync(() -> {
            Path decompiledFilePath = this.decompilationDir.resolve(clazz.getName() + ".java");

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

        Path output = this.decompilationDir.resolve(name + ".java");
        try {
            Files.write(output, ProcyonDecompiler.decompile(name).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to delete or write java file " + name, e);
        }

        return true;
    }

    public void setup() {
        this.dataDir = FMLClientHandler.instance().getSavesDirectory().toPath().getParent().resolve("code-viewer");
        this.decompilationDir = this.dataDir.resolve(DECOMPILED_FILES_FOLDER);

        createDirectory(this.dataDir);
        createDirectory(this.decompilationDir);
    }

    public void openGui(Class<?> clazz) {
        openGui(clazz, 0);
    }

    public void openGui(Class<?> clazz, int line) {
        CompletableFuture.runAsync(() -> {
            Path filePath = this.decompilationDir.resolve(clazz.getName() + ".java");
            if (!Files.exists(filePath))
                decompileClass(clazz);

            //open in companion app
            if (TotalDebug.PROXY.getClientConfig().useCompanionApp) {
                CompanionApp companionApp = TotalDebug.PROXY.getCompanionApp();
                companionApp.startAndConnect();

                if (companionApp.isConnected()) {
                    Minecraft.getMinecraft().player.sendMessage(
                            new TextComponentTranslation("companion_app.open_file",
                                    new TextComponentString(filePath.getFileName().toString())
                                            .setStyle(new Style().setColor(TextFormatting.WHITE))
                            ).setStyle(new Style().setColor(TextFormatting.GRAY))
                    );
                    companionApp.sendOpenFileRequest(filePath, line);
                }
            } else { //open in default gui
                getDecompiledFileContent(clazz).exceptionally(throwable -> {
                    throwable.printStackTrace();
                    return "";
                }).thenAccept(code -> {
                    Minecraft.getMinecraft().addScheduledTask(() -> {
                        CodeViewScreen screen = new CodeViewScreen();
                        FMLClientHandler.instance().showGuiScreen(screen);
                        screen.setJavaCode(code);
                    });
                });
            }
        }).exceptionally(throwable -> {
            TotalDebug.LOGGER.error("Unable to decompile class {}", clazz.getName());
            throwable.printStackTrace();
            return null;
        });
    }

    private void createDirectory(Path path) {
        try {
            if (Files.notExists(path))
                Files.createDirectory(path);
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to create directory " + path + "!", e);
        }
    }

    public Path getDecompilationDir() {
        return decompilationDir;
    }

    public Path getDataDir() {
        return dataDir;
    }
}
