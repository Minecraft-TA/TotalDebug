package com.github.minecraft_ta.totaldebug.packaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModulePackagingTest {
    @TempDir Path directory;

    @Test
    void modAndEmbeddedLibrariesResolveTogetherWithoutSplitPackages() throws Exception {
        Path mod = Path.of(System.getProperty("totaldebug.packagedMod"));
        List<Path> modules = new ArrayList<>(List.of(mod));
        try (JarFile jar = new JarFile(mod.toFile())) {
            for (var entry : jar.stream().filter(entry -> entry.getName().startsWith("META-INF/jarjar/")
                    && entry.getName().endsWith(".jar")).toList()) {
                Path library = this.directory.resolve(Path.of(entry.getName()).getFileName());
                try (var bytes = jar.getInputStream(entry)) {
                    Files.copy(bytes, library);
                }
                modules.add(library);
            }
        }
        ModuleFinder finder = ModuleFinder.of(modules.toArray(Path[]::new));
        var roots = finder.findAll().stream().map(ModuleReference::descriptor).map(descriptor -> descriptor.name()).toList();
        assertEquals(modules.size(), roots.size(), "Every packaged JAR must have a distinct module identity");
        Configuration.resolveAndBind(finder, List.of(ModuleLayer.boot().configuration()), ModuleFinder.of(), roots);
    }
}
