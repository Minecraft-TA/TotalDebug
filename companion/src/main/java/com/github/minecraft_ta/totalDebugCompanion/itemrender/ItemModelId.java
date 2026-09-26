package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import java.util.Objects;
import java.util.regex.Pattern;

/** A namespaced Minecraft model identifier, such as {@code minecraft:item/diamond}. */
public record ItemModelId(String namespace, String path) implements Comparable<ItemModelId> {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9/._-]+");

    public ItemModelId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid resource namespace: " + namespace);
        }
        if (!PATH.matcher(path).matches() || path.startsWith("/") || path.endsWith("/") || hasParentSegment(path)) {
            throw new IllegalArgumentException("Invalid resource path: " + path);
        }
    }

    public static ItemModelId parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        return separator < 0
                ? new ItemModelId("minecraft", value)
                : new ItemModelId(value.substring(0, separator), value.substring(separator + 1));
    }

    @Override
    public int compareTo(ItemModelId other) {
        int namespaceOrder = this.namespace.compareTo(other.namespace);
        return namespaceOrder != 0 ? namespaceOrder : this.path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return this.namespace + ':' + this.path;
    }

    String modelResourcePath() {
        return "assets/" + this.namespace + "/models/" + this.path + ".json";
    }

    String textureResourcePath() {
        return "assets/" + this.namespace + "/textures/" + this.path + ".png";
    }

    private static boolean hasParentSegment(String path) {
        for (String segment : path.split("/")) {
            if (segment.equals("..") || segment.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
