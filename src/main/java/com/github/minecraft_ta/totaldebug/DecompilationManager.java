package com.github.minecraft_ta.totaldebug;

import com.github.minecraft_ta.totaldebug.gui.codeviewer.CodeViewScreen;
import com.google.common.base.Charsets;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.client.FMLClientHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class DecompilationManager {

    private Path fernflowerPath;
    private Path dataDir;

    private boolean setupComplete;

    //TODO: load cache from files
    private final Set<String> decompiledFiles = new HashSet<>();

    public CompletableFuture<String> getDecompiledFileContent(Class<?> clazz) {
        if (!isSetupComplete())
            return CompletableFuture.completedFuture("");

        return CompletableFuture.supplyAsync(() -> {
            Path decompiledFilePath = dataDir.resolve(clazz.getName() + ".java");

            //if not yet decompiled or cache is out of sync -> decompile again
            if (!decompiledFiles.contains(clazz.getName()) || !Files.exists(decompiledFilePath)) {
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
        Path target = this.dataDir.resolve(name + ".class");
        String codeSource = clazz.getProtectionDomain().getCodeSource().getLocation().toString();
        if (codeSource.startsWith("jar"))
            codeSource = codeSource.substring(codeSource.lastIndexOf('!') + 2);
        else
            codeSource = clazz.getName().replace(".", "/") + ".class";

        try (InputStream inputStream = clazz.getClassLoader().getResourceAsStream(codeSource)) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to copy class to file!", e);
            return false;
        }

        try {
            Process p = new ProcessBuilder("java -jar " + fernflowerPath.toString() +
                    " \"" + target.toString() + "\"" +
                    " " + this.dataDir.toString())
                    .start();

            p.waitFor();
        } catch (IOException | InterruptedException e) {
            TotalDebug.LOGGER.error("Error while running fernflower!", e);
            return false;
        }

        decompiledFiles.add(name);

        return true;
    }

    public void exportFernflower() {
        this.dataDir = FMLClientHandler.instance().getSavesDirectory().toPath().getParent().resolve("code-viewer");

        this.fernflowerPath = dataDir.resolve("fernflower.jar");

        CompletableFuture.runAsync(() -> {
            try {
                if (Files.notExists(dataDir))
                    Files.createDirectory(dataDir);
            } catch (IOException e) {
                TotalDebug.LOGGER.error("Unable to create cache directory!", e);
                return;
            }

            //fernflower exists -> we're done
            if (Files.exists(fernflowerPath)) {
                this.setupComplete = true;
                return;
            }

            TotalDebug.LOGGER.info("Exporting fernflower...");

            try {
                Files.copy(DecompilationManager.class.getResourceAsStream("/fernflower.ThanksShadowJar"), this.fernflowerPath);
            } catch (IOException e) {
                TotalDebug.LOGGER.error("Error while exporting fernflower!", e);
            }

            if (Files.exists(fernflowerPath)) {
                TotalDebug.LOGGER.info("Successfully exported fernflower!");
                this.setupComplete = true;
            } else {
                TotalDebug.LOGGER.warn("Fernflower jar not found! Please contact the mod authors.");
            }
        });
    }

    public void openGui(Class<?> clazz) {
        getDecompiledFileContent(clazz).thenAccept(s -> {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                CodeViewScreen screen = new CodeViewScreen();
                FMLClientHandler.instance().showGuiScreen(screen);
                screen.setJavaCode(s);
            });
        });
    }

    public boolean isSetupComplete() {
        return setupComplete;
    }
}
