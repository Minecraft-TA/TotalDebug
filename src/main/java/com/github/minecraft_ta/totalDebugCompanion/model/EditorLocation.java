package com.github.minecraft_ta.totalDebugCompanion.model;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** A module-relative editor location. The full source location is retained for the tooltip. */
public record EditorLocation(String module, List<String> path, String tooltip) {
    private static final String SEPARATOR = "  ›  ";

    public EditorLocation {
        module = Objects.requireNonNullElse(module, "").trim();
        path = List.copyOf(Objects.requireNonNull(path, "path").stream()
                .filter(segment -> segment != null && !segment.isBlank())
                .toList());
        tooltip = Objects.requireNonNullElse(tooltip, "");
    }

    public static EditorLocation empty() {
        return new EditorLocation("", List.of(), "");
    }

    public static EditorLocation forFile(Path file, Path workspaceDirectory) {
        Path normalized = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Path workspace = workspaceDirectory == null ? null : workspaceDirectory.toAbsolutePath().normalize();
        if (workspace != null && normalized.startsWith(workspace)) {
            return new EditorLocation(
                    fileName(workspace),
                    segments(workspace.relativize(normalized)),
                    normalized.toString()
            );
        }
        Path parent = normalized.getParent();
        return new EditorLocation(
                parent == null ? "" : fileName(parent),
                List.of(fileName(normalized)),
                normalized.toString()
        );
    }

    public static EditorLocation forArchiveEntry(Path archive, String entryName) {
        Path normalized = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        String entry = normalizeEntry(entryName);
        return new EditorLocation(
                fileName(normalized),
                split(entry),
                normalized + "!/" + entry
        );
    }

    public static EditorLocation forRuntimeClass(String binaryName, String logicalSource) {
        return forRuntimeClass(binaryName, logicalSource, "");
    }

    public static EditorLocation forRuntimeClass(
            String binaryName,
            String logicalSource,
            String moduleDisplayName
    ) {
        String classPath = Objects.requireNonNull(binaryName, "binaryName").replace('.', '/') + ".java";
        String source = Objects.requireNonNullElse(logicalSource, "");
        String preferredModule = Objects.requireNonNullElse(moduleDisplayName, "").trim();
        if (source.startsWith("jrt:/")) {
            String module = source.substring("jrt:/".length());
            int separator = module.indexOf('/');
            if (separator >= 0) {
                module = module.substring(0, separator);
            }
            return new EditorLocation(
                    preferredModule.isBlank() ? module : preferredModule,
                    split(classPath),
                    source + "/" + classPath
            );
        }

        String baseSource = source;
        List<String> nestedPath = new ArrayList<>();
        int nestedSeparator = source.indexOf("!/");
        if (nestedSeparator >= 0) {
            baseSource = source.substring(0, nestedSeparator);
            nestedPath.addAll(split(source.substring(nestedSeparator + 2)));
        }
        try {
            URI sourceUri = URI.create(baseSource);
            if ("file".equalsIgnoreCase(sourceUri.getScheme())) {
                Path sourcePath = Path.of(sourceUri).toAbsolutePath().normalize();
                String sourceTooltip = java.nio.file.Files.isDirectory(sourcePath)
                        ? sourcePath.resolve(classPath.replace('/', java.io.File.separatorChar)).toString()
                        : source + "!/" + classPath;
                EditorLocation buildOutput = fromGradleBuildOutput(
                        sourcePath,
                        classPath,
                        sourceTooltip,
                        preferredModule
                );
                if (buildOutput != null) {
                    return buildOutput;
                }
                List<String> relative = new ArrayList<>(nestedPath);
                relative.addAll(split(classPath));
                return new EditorLocation(
                        preferredModule.isBlank() ? fileName(sourcePath) : preferredModule,
                        relative,
                        sourceTooltip
                );
            }
        } catch (IllegalArgumentException ignored) {
        }

        List<String> fallback = new ArrayList<>(nestedPath);
        fallback.addAll(split(classPath));
        return new EditorLocation(
                preferredModule.isBlank() ? sourceName(baseSource) : preferredModule,
                fallback,
                source + "!/" + classPath
        );
    }

    public String breadcrumb() {
        List<String> segments = new ArrayList<>();
        if (!this.module.isBlank()) {
            segments.add(this.module);
        }
        segments.addAll(this.path);
        if (segments.size() > 11) {
            List<String> visible = new ArrayList<>();
            visible.add(segments.getFirst());
            visible.addAll(segments.subList(1, 4));
            visible.add("…");
            visible.addAll(segments.subList(segments.size() - 5, segments.size()));
            segments = visible;
        }
        return String.join(SEPARATOR, segments);
    }

    private static EditorLocation fromGradleBuildOutput(
            Path source,
            String classPath,
            String tooltip,
            String preferredModule
    ) {
        List<String> names = segments(source);
        for (int index = 0; index + 3 < names.size(); index++) {
            if (!names.get(index).equalsIgnoreCase("build")
                    || !names.get(index + 1).equalsIgnoreCase("classes")) {
                continue;
            }
            String language = names.get(index + 2).toLowerCase(Locale.ROOT);
            String sourceSet = names.get(index + 3);
            Path moduleRoot = source;
            for (int remaining = names.size() - index; remaining > 0; remaining--) {
                moduleRoot = moduleRoot.getParent();
            }
            if (moduleRoot == null) {
                return null;
            }
            List<String> relative = new ArrayList<>(List.of("src", sourceSet, language));
            relative.addAll(split(classPath));
            return new EditorLocation(
                    preferredModule.isBlank() ? fileName(moduleRoot) : preferredModule,
                    relative,
                    tooltip
            );
        }
        return null;
    }

    private static List<String> segments(Path path) {
        List<String> result = new ArrayList<>();
        for (Path segment : path) {
            result.add(segment.toString());
        }
        return result;
    }

    private static List<String> split(String path) {
        List<String> result = new ArrayList<>();
        for (String segment : path.replace('\\', '/').split("/")) {
            if (!segment.isBlank()) {
                result.add(segment);
            }
        }
        return result;
    }

    private static String normalizeEntry(String entryName) {
        String normalized = Objects.requireNonNull(entryName, "entryName").replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String sourceName(String source) {
        int separator = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
        return separator < 0 ? source : source.substring(separator + 1);
    }

    private static String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }
}
