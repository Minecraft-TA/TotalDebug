package com.github.minecraft_ta.totaldebug.client.command;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

final class ClassNameCatalog {
    private final Map<Integer, NavigableMap<String, List<String>>> namesByDepth;

    ClassNameCatalog(Collection<String> classNames) {
        Map<Integer, NavigableMap<String, TreeSet<String>>> mutableNames = new HashMap<>();
        for (String className : classNames) {
            addName(mutableNames, className);

            int packageEnd = className.lastIndexOf('.');
            while (packageEnd > 0) {
                String packageName = className.substring(0, packageEnd);
                addName(mutableNames, packageName);
                packageEnd = packageName.lastIndexOf('.');
            }
        }

        Map<Integer, NavigableMap<String, List<String>>> names = new HashMap<>();
        mutableNames.forEach((depth, entries) -> {
            NavigableMap<String, List<String>> immutableEntries = new TreeMap<>();
            entries.forEach((normalizedName, originalNames) ->
                    immutableEntries.put(normalizedName, List.copyOf(originalNames)));
            names.put(depth, Collections.unmodifiableNavigableMap(immutableEntries));
        });
        this.namesByDepth = Map.copyOf(names);
    }

    static ClassNameCatalog scanRuntime() throws IOException {
        var runtime = TotalDebug.get().runtimeSources();
        return runtime.withCurrentSources(() -> scanSources(runtime.paths()));
    }

    private static ClassNameCatalog scanSources(List<Path> sources) throws IOException {
        List<String> classNames;
        try (ScanResult scan = new ClassGraph()
                .overrideClasspath(sources)
                .disableNestedJarScanning()
                .enableClassInfo()
                .scan()) {
            classNames = new ArrayList<>(scan.getAllClasses().getNames());
        }
        classNames.addAll(jdkClassNames());
        return new ClassNameCatalog(classNames);
    }

    List<String> suggest(String input) {
        if (input.isBlank()) {
            return List.of();
        }

        String normalizedInput = input.toLowerCase(Locale.ROOT);
        NavigableMap<String, List<String>> names = this.namesByDepth.get(dotCount(input));
        if (names == null) {
            return List.of();
        }

        List<String> suggestions = new ArrayList<>();
        for (var entry : names.tailMap(normalizedInput, true).entrySet()) {
            if (!entry.getKey().startsWith(normalizedInput)) {
                break;
            }
            suggestions.addAll(entry.getValue());
        }
        return List.copyOf(suggestions);
    }

    private static void addName(
            Map<Integer, NavigableMap<String, TreeSet<String>>> names,
            String name
    ) {
        names.computeIfAbsent(dotCount(name), ignored -> new TreeMap<>())
                .computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored -> new TreeSet<>())
                .add(name);
    }

    private static int dotCount(String value) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '.') {
                count++;
            }
        }
        return count;
    }

    private static List<String> jdkClassNames() throws IOException {
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path modules = jrt.getPath("/modules");
        List<String> names = new ArrayList<>();
        try (Stream<Path> modulePaths = Files.list(modules)) {
            for (Path module : modulePaths.toList()) {
                try (Stream<Path> classes = Files.walk(module)) {
                    classes.filter(Files::isRegularFile)
                            .map(module::relativize)
                            .map(Path::toString)
                            .map(path -> path.replace('\\', '/'))
                            .filter(ClassNameCatalog::isClassEntry)
                            .map(path -> path.substring(0, path.length() - ".class".length()).replace('/', '.'))
                            .forEach(names::add);
                }
            }
        }
        return names;
    }

    private static boolean isClassEntry(String path) {
        return path.endsWith(".class")
                && !path.equals("module-info.class")
                && !path.endsWith("/module-info.class")
                && !path.equals("package-info.class")
                && !path.endsWith("/package-info.class");
    }
}
