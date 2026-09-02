package com.github.minecraft_ta.totaldebug.runtime;

import net.neoforged.fml.ModList;

import java.io.IOException;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Discovers physical and virtual class sources in runtime ownership order. */
public final class RuntimeSourceInventory {
    public record Source(Path path, String moduleName) {
        public Source {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        }
    }

    private RuntimeSourceInventory() {
    }

    public static List<Source> discover(Class<?>... anchors) throws IOException {
        Set<Path> sources = new LinkedHashSet<>();
        Map<Path, String> moduleNames = new LinkedHashMap<>();
        ModList.get().forEachModFile(modFile -> {
            Path path = modFile.getFilePath().toAbsolutePath().normalize();
            sources.add(path);
            moduleNames.put(path, modFile.getModFileInfo().moduleName());
        });
        Set<ModuleLayer> visitedLayers = new LinkedHashSet<>();
        for (Class<?> anchor : anchors) {
            addModuleLayerSources(sources, moduleNames, anchor.getModule().getLayer(), visitedLayers);
            if (!moduleNames.containsValue(anchor.getModule().getName())) {
                addCodeSource(sources, anchor);
            }
        }
        addJavaClasspathSources(sources, anchors);

        return existingSources(sources).stream().map(path -> new Source(path, moduleNames.get(path))).toList();
    }

    static List<Path> existingSources(Set<Path> sources) throws IOException {
        List<Path> existingSources = new ArrayList<>();
        for (Path source : sources) {
            if (!Files.exists(source)) {
                throw new IOException("Runtime class source does not exist: " + source);
            }
            if (Files.isDirectory(source) || isJar(source)) {
                existingSources.add(source);
            }
        }
        return List.copyOf(existingSources);
    }

    public static Map<Class<?>, Path> sourcesContaining(List<Path> sources, Class<?>... anchors) throws IOException {
        Map<String, Class<?>> anchorsByEntry = new LinkedHashMap<>();
        for (Class<?> anchor : anchors) {
            anchorsByEntry.put(anchor.getName().replace('.', '/') + ".class", anchor);
        }
        Set<String> remainingEntries = new LinkedHashSet<>(anchorsByEntry.keySet());
        Map<Class<?>, Path> owners = new LinkedHashMap<>();
        for (Path source : List.copyOf(sources)) {
            Set<String> ownedEntries = containedEntries(source, remainingEntries);
            for (String classEntry : ownedEntries) {
                owners.put(anchorsByEntry.get(classEntry), source.toAbsolutePath().normalize());
            }
            remainingEntries.removeAll(ownedEntries);
            if (remainingEntries.isEmpty()) {
                break;
            }
        }
        return Map.copyOf(owners);
    }

    private static void addModuleLayerSources(
            Set<Path> sources,
            Map<Path, String> moduleNames,
            ModuleLayer layer,
            Set<ModuleLayer> visitedLayers
    ) throws IOException {
        if (layer == null || !visitedLayers.add(layer)) {
            return;
        }
        for (ResolvedModule module : layer.configuration().modules().stream()
                .sorted(Comparator.comparing(ResolvedModule::name)).toList()) {
            if (moduleNames.containsValue(module.name())) {
                continue;
            }
            Path path = modulePath(module.name(), module.reference().location().orElse(null));
            if (path == null) {
                continue;
            }
            sources.add(path);
            moduleNames.putIfAbsent(path, module.name());
        }
        for (ModuleLayer parent : layer.parents()) {
            addModuleLayerSources(sources, moduleNames, parent, visitedLayers);
        }
    }

    static Path modulePath(String moduleName, URI location) throws IOException {
        // Generated modules have no backing source; JDK modules are supplied by the JDK itself.
        if (location == null || "jrt".equalsIgnoreCase(location.getScheme())) {
            return null;
        }
        try {
            return Path.of(location).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new IOException("Cannot resolve runtime module " + moduleName + " at " + location, exception);
        }
    }

    private static void addJavaClasspathSources(Set<Path> sources, Class<?>... anchors) throws IOException {
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isBlank()) {
            return;
        }
        List<Path> classpathSources = new ArrayList<>();
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                classpathSources.add(Path.of(entry));
            }
        }
        addClasspathSources(sources, classpathSources, anchors);
    }

    static void addClasspathSources(
            Set<Path> sources,
            List<Path> classpathSources,
            Class<?>... anchors
    ) throws IOException {
        Set<String> anchorEntries = new HashSet<>();
        Arrays.stream(anchors)
                .map(anchor -> anchor.getName().replace('.', '/') + ".class")
                .forEach(anchorEntries::add);
        Set<String> ownedAnchorEntries = new HashSet<>();
        for (Path source : sources) {
            ownedAnchorEntries.addAll(containedEntries(source, anchorEntries));
        }

        for (Path source : classpathSources) {
            Path normalized = source.toAbsolutePath().normalize();
            if (sources.contains(normalized)) {
                continue;
            }
            Set<String> containedAnchorEntries = containedEntries(normalized, anchorEntries);
            if (containedAnchorEntries.stream().anyMatch(ownedAnchorEntries::contains)) {
                continue;
            }
            sources.add(normalized);
            ownedAnchorEntries.addAll(containedAnchorEntries);
        }
    }

    private static Set<String> containedEntries(Path source, Set<String> candidates) throws IOException {
        Set<String> contained = new HashSet<>();
        if (Files.isDirectory(source)) {
            for (String candidate : candidates) {
                if (Files.isRegularFile(source.resolve(candidate))) {
                    contained.add(candidate);
                }
            }
            return contained;
        }
        if (!isJar(source)) {
            return contained;
        }
        if (source.getFileSystem() == FileSystems.getDefault()) {
            try (ZipFile zip = new ZipFile(source.toFile())) {
                for (String candidate : candidates) {
                    if (zip.getEntry(candidate) != null) {
                        contained.add(candidate);
                    }
                }
            }
            return contained;
        }
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null && contained.size() < candidates.size()) {
                if (!entry.isDirectory() && candidates.contains(entry.getName())) {
                    contained.add(entry.getName());
                }
            }
        }
        return contained;
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
