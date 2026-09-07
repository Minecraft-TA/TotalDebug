package com.github.minecraft_ta.totalDebugCompanion.script;

import com.google.gson.JsonParseException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Companion-side model for the canonical value graph captured in Minecraft. */
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
            if (!validChildKind(kind, child.kind)) {
                throw new IllegalArgumentException("Invalid child kind " + child.kind + " for " + kind);
            }
        }
    }

    public String displayValue(int maxCharacters) {
        if (maxCharacters < 0) {
            throw new IllegalArgumentException("maxCharacters must not be negative");
        }
        int retainedCharacters = Math.min(this.value.text().length(), maxCharacters);
        if (retainedCharacters > 0 && retainedCharacters < this.value.text().length()
                && Character.isHighSurrogate(this.value.text().charAt(retainedCharacters - 1))
                && Character.isLowSurrogate(this.value.text().charAt(retainedCharacters))) {
            retainedCharacters--;
        }
        boolean shortened = retainedCharacters < this.value.text().length() || this.value.truncated();
        String retained = this.value.text().substring(0, retainedCharacters) + (shortened ? "…" : "");
        return switch (this.kind) {
            case NULL -> "null";
            case STRING -> '"' + escape(retained, '"') + '"';
            case CHARACTER -> "'" + escape(retained, '\'') + "'";
            case REFERENCE -> "reference #" + this.identity;
            default -> retained;
        };
    }

    /** Converts complete ordinary values to natural JSON and keeps metadata for Java-specific values. */
    public Object toJsonValue() {
        Set<Integer> referencedIdentities = new HashSet<>();
        collectReferences(this, referencedIdentities);
        return toJsonValue(referencedIdentities);
    }

    private Object toJsonValue(Set<Integer> referencedIdentities) {
        return switch (this.kind) {
            case NULL -> null;
            case BOOLEAN, NUMBER, CHARACTER, STRING -> scalarJson();
            case ENUM, CLASS -> scalarEnvelope();
            case ARRAY, COLLECTION -> this.truncated || referencedIdentities.contains(this.identity)
                    ? sequenceEnvelope(referencedIdentities)
                    : sequenceValues(referencedIdentities);
            case MAP -> stringMap(referencedIdentities)
                    .orElseGet(() -> mapEnvelope(referencedIdentities));
            case OPTIONAL -> optionalJson(referencedIdentities);
            case OBJECT -> objectEnvelope(referencedIdentities);
            case REFERENCE -> referenceEnvelope();
            case ERROR -> errorEnvelope();
        };
    }

    static void validate(ExecutionValue value, int depth, int[] nodes) {
        if (depth > 8 || ++nodes[0] > 5_000) {
            throw new JsonParseException("Execution value exceeds the supported graph bounds");
        }
        if (value == null || value.kind == null || value.type == null || value.value == null
                || value.preview == null || value.children == null || value.identity < 0
                || value.totalChildren < value.children.size()
                || value.truncated != (value.totalChildren > value.children.size())) {
            throw new JsonParseException("Execution value contains invalid metadata");
        }
        boolean identified = switch (value.kind) {
            case OPTIONAL, ARRAY, COLLECTION, MAP, OBJECT, REFERENCE -> true;
            default -> false;
        };
        if (identified != (value.identity > 0)) {
            throw new JsonParseException("Execution value contains invalid identity metadata");
        }
        validateText(value.value, "value");
        validateText(value.preview, "preview");
        validateText(value.type, "type");
        if (!value.value.truncated() && value.kind == Kind.BOOLEAN
                && !value.value.text().equals("true") && !value.value.text().equals("false")) {
            throw new JsonParseException("Execution value contains an invalid boolean");
        }
        if (!value.value.truncated() && value.kind == Kind.NUMBER) {
            try {
                String number = value.value.text();
                if (!number.equals("NaN") && !number.equals("Infinity") && !number.equals("-Infinity")) {
                    number(number);
                }
            } catch (NumberFormatException exception) {
                throw new JsonParseException("Execution value contains an invalid number", exception);
            }
        }
        for (Child child : value.children) {
            if (child == null || child.name == null || child.kind == null || child.value == null
                    || (child.kind == ChildKind.MAP_ENTRY) != (child.key != null)
                    || !validChildKind(value.kind, child.kind)) {
                throw new JsonParseException("Execution value contains an invalid child");
            }
            validateText(child.name, "child name");
            if (child.key != null) {
                validate(child.key, depth + 1, nodes);
            }
            validate(child.value, depth + 1, nodes);
        }
    }

    private Object optionalJson(Set<Integer> referencedIdentities) {
        if (referencedIdentities.contains(this.identity)) {
            return objectEnvelope(referencedIdentities);
        }
        if (!this.truncated && this.children.isEmpty()) {
            return null;
        }
        if (!this.truncated && this.children.size() == 1) {
            return this.children.getFirst().value.toJsonValue(referencedIdentities);
        }
        return objectEnvelope(referencedIdentities);
    }

    private List<Object> sequenceValues(Set<Integer> referencedIdentities) {
        return this.children.stream()
                .map(child -> child.value.toJsonValue(referencedIdentities))
                .toList();
    }

    private Map<String, Object> sequenceEnvelope(Set<Integer> referencedIdentities) {
        Map<String, Object> result = metadata();
        result.put("values", sequenceValues(referencedIdentities));
        addTruncation(result);
        return result;
    }

    private java.util.Optional<Map<String, Object>> stringMap(Set<Integer> referencedIdentities) {
        if (this.truncated || referencedIdentities.contains(this.identity)) {
            return java.util.Optional.empty();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Child child : this.children) {
            if (child.key.kind != Kind.STRING || child.key.value.truncated()
                    || result.containsKey(child.key.value.text())) {
                return java.util.Optional.empty();
            }
            result.put(child.key.value.text(), child.value.toJsonValue(referencedIdentities));
        }
        return java.util.Optional.of(result);
    }

    private Map<String, Object> mapEnvelope(Set<Integer> referencedIdentities) {
        Map<String, Object> result = metadata();
        List<Map<String, Object>> entries = new ArrayList<>(this.children.size());
        for (Child child : this.children) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", child.key.toJsonValue(referencedIdentities));
            entry.put("value", child.value.toJsonValue(referencedIdentities));
            entries.add(entry);
        }
        result.put("entries", entries);
        addTruncation(result);
        return result;
    }

    private Map<String, Object> objectEnvelope(Set<Integer> referencedIdentities) {
        Map<String, Object> result = metadata();
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Child child : this.children) {
            String name = child.name.text();
            for (int duplicate = 2; fields.containsKey(name); duplicate++) {
                name = child.name.text() + '#' + duplicate;
            }
            fields.put(name, child.value.toJsonValue(referencedIdentities));
        }
        result.put("fields", fields);
        addTruncation(result);
        return result;
    }

    private Map<String, Object> referenceEnvelope() {
        Map<String, Object> result = metadata();
        result.put("reference", this.identity);
        return result;
    }

    private Map<String, Object> errorEnvelope() {
        Map<String, Object> result = metadata();
        result.put("error", this.value.text());
        addTextMetadata(result, "error", this.value);
        return result;
    }

    private Map<String, Object> metadata() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", this.type.text());
        addTextMetadata(result, "type", this.type);
        if (!this.preview.text().isBlank()) {
            result.put("preview", this.preview.text());
            addTextMetadata(result, "preview", this.preview);
        }
        if (this.identity > 0) {
            result.put("identity", this.identity);
        }
        return result;
    }

    private void addTruncation(Map<String, Object> result) {
        if (this.truncated) {
            result.put("truncated", true);
            result.put("captured_children", this.children.size());
            result.put("total_children", this.totalChildren);
        }
    }

    private static Number number(String value) {
        if (value.equals("NaN") || value.equals("Infinity") || value.equals("-Infinity")) {
            throw new NumberFormatException("Non-finite numbers are not JSON scalars");
        }
        return value.matches("[-+]?\\d+") ? new BigInteger(value) : new BigDecimal(value);
    }

    private Object scalarJson() {
        if (this.value.truncated()) {
            return scalarEnvelope();
        }
        return switch (this.kind) {
            case BOOLEAN -> Boolean.valueOf(this.value.text());
            case NUMBER -> {
                try {
                    yield number(this.value.text());
                } catch (NumberFormatException ignored) {
                    yield scalarEnvelope();
                }
            }
            case CHARACTER, STRING -> this.value.text();
            default -> throw new IllegalStateException("Not a scalar execution value: " + this.kind);
        };
    }

    private Map<String, Object> scalarEnvelope() {
        Map<String, Object> result = metadata();
        result.put("value", this.value.text());
        addTextMetadata(result, "value", this.value);
        return result;
    }

    private static void addTextMetadata(Map<String, Object> target, String name, ExecutionText text) {
        if (text.truncated()) {
            target.put(name + "_truncated", true);
            target.put(name + "_total_characters", text.totalCharacters());
        }
    }

    private static void validateText(ExecutionText text, String name) {
        if (text.text() == null || text.totalCharacters() < text.text().length()
                || text.truncated() != (text.totalCharacters() > text.text().length())) {
            throw new JsonParseException("Execution value contains invalid " + name + " metadata");
        }
    }

    private static void collectReferences(ExecutionValue value, Set<Integer> identities) {
        if (value.kind == Kind.REFERENCE) {
            identities.add(value.identity);
            return;
        }
        for (Child child : value.children) {
            if (child.key != null) {
                collectReferences(child.key, identities);
            }
            collectReferences(child.value, identities);
        }
    }

    private static String escape(String text, char quote) {
        String escaped = text.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return quote == '"' ? escaped.replace("\"", "\\\"") : escaped.replace("'", "\\'");
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

    private static boolean validChildKind(Kind parent, ChildKind child) {
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
    }
}
