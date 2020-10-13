package com.github.tth05.codeviewer;

import com.google.common.base.Charsets;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;

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
                CodeViewer.LOGGER.error("Error while reading decompiled file!", e);
                return "";
            }
        });
    }

    public boolean decompileClass(Class<?> clazz) {
        String name = clazz.getName();
        Path target = this.dataDir.resolve(name + ".class");
        try (InputStream inputStream = Object.class.getResourceAsStream("/" + name.replace(".", "/") + ".class")) {
            System.out.println(Files.exists(target));
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            CodeViewer.LOGGER.error("Unable to copy class to file!", e);
            return false;
        }

        try {
            Process p = new ProcessBuilder("java -jar " + fernflowerPath.toString() +
                    " \"" + target.toString() + "\"" +
                    " " + this.dataDir.toString())
                    .start();

            p.waitFor();
        } catch (IOException | InterruptedException e) {
            CodeViewer.LOGGER.error("Error while running fernflower!", e);
            return false;
        }

        decompiledFiles.add(name);

        return true;
    }

    public void downloadFernflower() {
        this.dataDir = FMLCommonHandler.instance().getMinecraftServerInstance().getDataDirectory().toPath()
                .resolve("code-viewer");

        this.fernflowerPath = dataDir.resolve("fernflower/build/libs/fernflower.jar");

        CompletableFuture.runAsync(() -> {
            try {
                if (Files.notExists(dataDir))
                    Files.createDirectory(dataDir);
            } catch (IOException e) {
                CodeViewer.LOGGER.error("Unable to create cache directory!", e);
                return;
            }

            //fernflower exists -> we're done
            if (Files.exists(fernflowerPath)) {
                this.setupComplete = true;
                return;
            }

            //delete old fernflower folder
            try {
                FileUtils.deleteDirectory(this.dataDir.resolve("fernflower").toFile());
            } catch (IOException ignored) {
            }

            CodeViewer.LOGGER.info("Downloading and building fernflower...");

            try {
                ProcessBuilder pb;
                if (SystemUtils.IS_OS_WINDOWS)
                    pb = new ProcessBuilder("cmd.exe", "/c",
                            "cd code-viewer && git clone https://github.com/fesh0r/fernflower.git && cd fernflower && gradlew.bat build");
                else
                    pb = new ProcessBuilder("bash", "-c",
                            "cd code-viewer && git clone https://github.com/fesh0r/fernflower.git && cd fernflower && ./gradlew build");

                pb.inheritIO().start().waitFor();
            } catch (InterruptedException | IOException e) {
                CodeViewer.LOGGER.error("Error while downloading and building fernflower", e);
                return;
            }

            if (Files.exists(fernflowerPath)) {
                CodeViewer.LOGGER.info("Successfully built fernflower!");
                this.setupComplete = true;
            } else {
                CodeViewer.LOGGER.warn("Fernflower jar not found! Please restart to try again.");
            }
        });
    }

    public boolean isSetupComplete() {
        return setupComplete;
    }
}
