package com.github.tth05.codeviewer;

import net.minecraftforge.fml.common.FMLCommonHandler;
import org.apache.commons.lang3.SystemUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DecompilationManager {

    private Path fernflowerPath;
    private Path dataDir;

    private final Set<String> decompiledFiles = new HashSet<>();

    public List<String> getDecompiledFile(Class<?> clazz) {
        if (!decompiledFiles.contains(clazz.getName())) {
            decompileClass(clazz);
        }
        try {
            return Files.readAllLines(dataDir.resolve(clazz.getName() + ".java"));
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public void decompileClass(Class<?> clazz) {
        String name = clazz.getName();
        Path target = this.dataDir.resolve(name + ".class");
        try (InputStream inputStream = Object.class.getResourceAsStream("/" + name.replace(".", "/") + ".class")) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            CodeViewer.LOGGER.error("Unable to copy class to file!", e);
        }

        try {
            Process p = new ProcessBuilder("java -jar " + fernflowerPath.toString() +
                    " \"" + target.toString() + "\"" +
                    " " + this.dataDir.toString())
                    .start();

            p.waitFor();
        } catch (IOException | InterruptedException e) {

        }

        decompiledFiles.add(name);
    }

    public void downloadFernflower() {
        this.dataDir = FMLCommonHandler.instance().getMinecraftServerInstance().getDataDirectory().toPath()
                .resolve("code-viewer");
        this.fernflowerPath = dataDir.resolve("fernflower/build/libs/fernflower.jar");

        try {
            if (Files.notExists(dataDir))
                Files.createDirectory(dataDir);
        } catch (IOException e) {
            CodeViewer.LOGGER.error("Unable to create cache directory!", e);
        }

        if (Files.exists(fernflowerPath))
            return;

        try {
            Files.deleteIfExists(this.dataDir.resolve("fernflower"));
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

        if (Files.exists(fernflowerPath))
            CodeViewer.LOGGER.info("Successfully built fernflower!");
        else
            CodeViewer.LOGGER.warn("Fernflower jar not found! Please restart to try again.");
    }
}
