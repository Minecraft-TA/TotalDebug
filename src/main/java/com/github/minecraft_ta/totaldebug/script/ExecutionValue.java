package com.github.minecraft_ta.totaldebug.script;

import java.util.List;
import java.util.Objects;

/** Immutable value graph captured from a live Java execution. */
public record ExecutionValue(
        ExecutionText type,
        ExecutionText value,
        ExecutionText preview,
        Kind kind,
        int identity,
        int totalChildren,
        boolean truncated,
        List<Child> children
) {
    public ExecutionValue {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(kind, "kind");
        children = List.copyOf(Objects.requireNonNull(children, "children"));
        if (identity < 0 || totalChildren < children.size()
                || truncated != (totalChildren > children.size())) {
            throw new IllegalArgumentException("Invalid execution-value metadata");
        }
        boolean identified = switch (kind) {
            case OPTIONAL, ARRAY, COLLECTION, MAP, OBJECT, REFERENCE -> true;
            default -> false;
        };
        if (identified != (identity > 0)) {
            throw new IllegalArgumentException("Invalid execution-value identity");
        }
        for (Child child : children) {
            if (!validChildKind(kind, child.kind())) {
                throw new IllegalArgumentException("Invalid child kind " + child.kind() + " for " + kind);
            }
        }
    }

    public enum Kind {
        NULL,
        BOOLEAN,
        NUMBER,
        CHARACTER,
        STRING,
        ENUM,
        CLASS,
        OPTIONAL,
        ARRAY,
        COLLECTION,
        MAP,
        OBJECT,
        REFERENCE,
        ERROR
    }

    public enum ChildKind {
        FIELD,
        RECORD_COMPONENT,
        ARRAY_ELEMENT,
        COLLECTION_ELEMENT,
        MAP_ENTRY,
        OPTIONAL_VALUE
    }

    static boolean validChildKind(Kind parent, ChildKind child) {
        return switch (parent) {
            case OPTIONAL -> child == ChildKind.OPTIONAL_VALUE;
            case ARRAY -> child == ChildKind.ARRAY_ELEMENT;
            case COLLECTION -> child == ChildKind.COLLECTION_ELEMENT;
            case MAP -> child == ChildKind.MAP_ENTRY;
            case OBJECT -> child == ChildKind.FIELD || child == ChildKind.RECORD_COMPONENT;
            default -> false;
        };
    }

    public record Child(ExecutionText name, ChildKind kind, ExecutionValue key, ExecutionValue value) {
        public Child {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
            if ((kind == ChildKind.MAP_ENTRY) != (key != null)) {
                throw new IllegalArgumentException("Only map entries carry a structured key");
            }
        }

        public static Child named(String name, ChildKind kind, ExecutionValue value) {
            if (kind == ChildKind.MAP_ENTRY) {
                throw new IllegalArgumentException("Map entries require a structured key");
            }
            return new Child(ExecutionText.complete(name), kind, null, value);
        }

        public static Child mapEntry(ExecutionValue key, ExecutionValue value) {
            return new Child(
                    ExecutionText.empty(),
                    ChildKind.MAP_ENTRY,
                    Objects.requireNonNull(key, "key"),
                    value
            );
        }
    }
}
