package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import java.nio.file.Path;
import java.util.Objects;

/** A directory or archive, optionally viewed from a resource-pack directory inside it. */
public record ItemRenderResourceRoot(Path path, String prefix) {

    public ItemRenderResourceRoot {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        prefix = normalizePrefix(prefix);
    }

    public ItemRenderResourceRoot(Path path) {
        this(path, "");
    }

    public static ItemRenderResourceRoot nested(Path archive, String prefix) {
        return new ItemRenderResourceRoot(archive, prefix);
    }

    public String description() {
        return this.prefix.isEmpty() ? this.path.toString() : this.path + "!/" + this.prefix;
    }

    String entryPath(String resourcePath) {
        return this.prefix + resourcePath;
    }

    private static String normalizePrefix(String value) {
        Objects.requireNonNull(value, "prefix");
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "";
        }
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid resource-root prefix: " + value);
            }
        }
        return normalized + "/";
    }
}
