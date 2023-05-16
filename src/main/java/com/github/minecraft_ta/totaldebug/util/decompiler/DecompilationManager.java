package com.github.minecraft_ta.totaldebug.util.decompiler;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.CompanionApp;
import com.github.minecraft_ta.totaldebug.companionApp.messages.codeView.DecompileOrOpenMessage;
import cpw.mods.fml.client.FMLClientHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class DecompilationManager {

    public static final String DECOMPILED_FILES_FOLDER = "decompiled-files";

    private Path dataDir;
    private Path decompilationDir;

    public void decompileClassIfNotExists(Class<?> clazz) {
        String name = clazz.getName();

        Path output = this.decompilationDir.resolve(name + ".java");
        if (Files.exists(output))
            return;

        try {
            Files.write(output, ProcyonDecompiler.decompile(name).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to delete or write java file " + name, e);
            throw new RuntimeException(e);
        }
    }

    public void setup() {
        this.dataDir = FMLClientHandler.instance().getSavesDirectory().toPath().getParent().resolve("total-debug");
        this.decompilationDir = this.dataDir.resolve(CompanionApp.DATA_FOLDER).resolve(DECOMPILED_FILES_FOLDER);

        createDirectory(this.dataDir);
        createDirectory(this.decompilationDir);
    }

    public void openGui(Class<?> clazz) {
        openGui(clazz, -1, null);
    }

    public void openGui(Class<?> clazz, int targetMemberType, String targetMemberIdentifier) {
        CompletableFuture.runAsync(() -> {
            Path filePath = this.decompilationDir.resolve(clazz.getName() + ".java");
            decompileClassIfNotExists(clazz);

            CompanionApp companionApp = TotalDebug.PROXY.getCompanionApp();
            companionApp.startAndConnect();

            if (companionApp.isConnected() && companionApp.waitForUI()) {
                Minecraft.getMinecraft().thePlayer.addChatComponentMessage(
                        new ChatComponentTranslation("companion_app.open_file",
                                new ChatComponentText(filePath.getFileName().toString())
                                        .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.WHITE))
                        ).setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY))
                );
                companionApp.getClient().getMessageProcessor().enqueueMessage(new DecompileOrOpenMessage(filePath, targetMemberType, targetMemberIdentifier));
            }
        }).exceptionally(throwable -> {
            TotalDebug.LOGGER.error("Unable to decompile class {}", clazz.getName(), throwable);
            return null;
        });
    }

    private void createDirectory(Path path) {
        try {
            if (Files.notExists(path))
                Files.createDirectories(path);
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
