package com.github.minecraft_ta.totaldebug.client.decompile;

import net.neoforged.fml.ModList;

import java.io.IOException;
import java.lang.module.ResolvedModule;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public final class RuntimeClassSources {
    private RuntimeClassSources() {
    }

    public static List<Path> discover(Class<?>... anchors) throws IOException {
        Set<Path> sources = new LinkedHashSet<>();
        Consumer<Path> addSource = path -> {
            if (path != null) {
                sources.add(path.toAbsolutePath().normalize());
            }
        };

        ModList.get().forEachModFile(modFile -> addSource.accept(modFile.getFilePath()));
        Set<ModuleLayer> visitedLayers = new LinkedHashSet<>();
        for (Class<?> anchor : anchors) {
            addModuleLayerSources(sources, anchor.getModule().getLayer(), visitedLayers);
            addCodeSource(sources, anchor);
        }
        addJavaClasspathSources(sources);

        List<Path> existingSources = new ArrayList<>();
        for (Path source : sources) {
            if (!Files.exists(source)) {
                throw new IOException("Runtime class source does not exist: " + source);
            }
            if (Files.isDirectory(source) || isJar(source)) {
                existingSources.add(source);
            }
        }
        existingSources.sort(Comparator.comparing(Path::toString));
        return List.copyOf(existingSources);
    }

    private static void addModuleLayerSources(
            Set<Path> sources,
            ModuleLayer layer,
            Set<ModuleLayer> visitedLayers
    ) throws IOException {
        if (layer == null || !visitedLayers.add(layer)) {
            return;
        }
        for (ResolvedModule module : layer.configuration().modules()) {
            var location = module.reference().location().orElse(null);
            if (location == null || "jrt".equalsIgnoreCase(location.getScheme())) {
                continue;
            }
            if (!"file".equalsIgnoreCase(location.getScheme())) {
                continue;
            }
            try {
                sources.add(Path.of(location).toAbsolutePath().normalize());
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid module location for " + module.name() + ": " + location, exception);
            }
        }
        for (ModuleLayer parent : layer.parents()) {
            addModuleLayerSources(sources, parent, visitedLayers);
        }
    }

    private static void addJavaClasspathSources(Set<Path> sources) {
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isBlank()) {
            return;
        }
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                sources.add(Path.of(entry).toAbsolutePath().normalize());
            }
        }
    }

    private static void addCodeSource(Set<Path> sources, Class<?> anchor) throws IOException {
        CodeSource codeSource = anchor.getProtectionDomain() == null
                ? null
                : anchor.getProtectionDomain().getCodeSource();
        URL location = codeSource == null ? null : codeSource.getLocation();
        if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
            return;
        }
        try {
            sources.add(Path.of(location.toURI()).toAbsolutePath().normalize());
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IOException("Invalid code-source location for " + anchor.getName() + ": " + location, exception);
        }
    }

    private static boolean isJar(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".jar") || name.endsWith(".zip"));
    }
}
