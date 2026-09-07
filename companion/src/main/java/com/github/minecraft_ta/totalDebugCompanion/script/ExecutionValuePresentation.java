package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionValue;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionText;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionValue.Kind;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionValue.Child;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Desktop rendering and MCP projection of a captured execution value. */
public final class ExecutionValuePresentation {
    private final ExecutionValue graph;
    public ExecutionValuePresentation(ExecutionValue graph) { this.graph = Objects.requireNonNull(graph); }
    public String displayValue(int maxCharacters) {
        if (maxCharacters < 0) {
            throw new IllegalArgumentException("maxCharacters must not be negative");
        }
        int retainedCharacters = Math.min(this.graph.value().text().length(), maxCharacters);
        if (retainedCharacters > 0 && retainedCharacters < this.graph.value().text().length()
                && Character.isHighSurrogate(this.graph.value().text().charAt(retainedCharacters - 1))
                && Character.isLowSurrogate(this.graph.value().text().charAt(retainedCharacters))) {
            retainedCharacters--;
        }
        boolean shortened = retainedCharacters < this.graph.value().text().length() || this.graph.value().truncated();
        String retained = this.graph.value().text().substring(0, retainedCharacters) + (shortened ? "…" : "");
        return switch (this.graph.kind()) {
            case NULL -> "null";
            case STRING -> '"' + escape(retained, '"') + '"';
            case CHARACTER -> "'" + escape(retained, '\'') + "'";
            case REFERENCE -> "reference #" + this.graph.identity();
            default -> retained;
        };
    }

    /** Converts complete ordinary values to natural JSON and keeps metadata for Java-specific values. */
    public Object toJsonValue() {
        Set<Integer> referencedIdentities = new HashSet<>();
        collectReferences(this.graph, referencedIdentities);
        return toJsonValue(referencedIdentities);
    }

    private Object toJsonValue(Set<Integer> referencedIdentities) {
        return switch (this.graph.kind()) {
            case NULL -> null;
            case BOOLEAN, NUMBER, CHARACTER, STRING -> scalarJson();
            case ENUM, CLASS -> scalarEnvelope();
            case ARRAY, COLLECTION -> this.graph.truncated() || referencedIdentities.contains(this.graph.identity())
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

    private Object optionalJson(Set<Integer> referencedIdentities) {
        if (referencedIdentities.contains(this.graph.identity())) {
            return objectEnvelope(referencedIdentities);
        }
        if (!this.graph.truncated() && this.graph.children().isEmpty()) {
            return null;
        }
        if (!this.graph.truncated() && this.graph.children().size() == 1) {
            return new ExecutionValuePresentation(this.graph.children().getFirst().value()).toJsonValue(referencedIdentities);
        }
        return objectEnvelope(referencedIdentities);
    }

    private List<Object> sequenceValues(Set<Integer> referencedIdentities) {
        return this.graph.children().stream()
                .map(child -> new ExecutionValuePresentation(child.value()).toJsonValue(referencedIdentities))
                .toList();
    }

    private Map<String, Object> sequenceEnvelope(Set<Integer> referencedIdentities) {
        Map<String, Object> result = metadata();
        result.put("values", sequenceValues(referencedIdentities));
        addTruncation(result);
        return result;
    }

    private java.util.Optional<Map<String, Object>> stringMap(Set<Integer> referencedIdentities) {
        if (this.graph.truncated() || referencedIdentities.contains(this.graph.identity())) {
            return java.util.Optional.empty();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Child child : this.graph.children()) {
            if (child.key().kind() != Kind.STRING || child.key().value().truncated()
                    || result.containsKey(child.key().value().text())) {
                return java.util.Optional.empty();
            }
            result.put(child.key().value().text(), new ExecutionValuePresentation(child.value()).toJsonValue(referencedIdentities));
        }
        return java.util.Optional.of(result);
    }

    private Map<String, Object> mapEnvelope(Set<Integer> referencedIdentities) {
        Map<String, Object> result = metadata();
        List<Map<String, Object>> entries = new ArrayList<>(this.graph.children().size());
        for (Child child : this.graph.children()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", new ExecutionValuePresentation(child.key()).toJsonValue(referencedIdentities));
            entry.put("value", new ExecutionValuePresentation(child.value()).toJsonValue(referencedIdentities));
            entries.add(entry);
        }
        result.put("entries", entries);
        addTruncation(result);
        return result;
    }

    private Map<String, Object> objectEnvelope(Set<Integer> referencedIdentities) {
        Map<String, Object> result = metadata();
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Child child : this.graph.children()) {
            String name = child.name().text();
            for (int duplicate = 2; fields.containsKey(name); duplicate++) {
                name = child.name().text() + '#' + duplicate;
            }
            fields.put(name, new ExecutionValuePresentation(child.value()).toJsonValue(referencedIdentities));
        }
        result.put("fields", fields);
        addTruncation(result);
        return result;
    }

    private Map<String, Object> referenceEnvelope() {
        Map<String, Object> result = metadata();
        result.put("reference", this.graph.identity());
        return result;
    }

    private Map<String, Object> errorEnvelope() {
        Map<String, Object> result = metadata();
        result.put("error", this.graph.value().text());
        addTextMetadata(result, "error", this.graph.value());
        return result;
    }

    private Map<String, Object> metadata() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", this.graph.type().text());
        addTextMetadata(result, "type", this.graph.type());
        if (!this.graph.preview().text().isBlank()) {
            result.put("preview", this.graph.preview().text());
            addTextMetadata(result, "preview", this.graph.preview());
        }
        if (this.graph.identity() > 0) {
            result.put("identity", this.graph.identity());
        }
        return result;
    }

    private void addTruncation(Map<String, Object> result) {
        if (this.graph.truncated()) {
            result.put("truncated", true);
            result.put("captured_children", this.graph.children().size());
            result.put("total_children", this.graph.totalChildren());
        }
    }

    private static Number number(String value) {
        if (value.equals("NaN") || value.equals("Infinity") || value.equals("-Infinity")) {
            throw new NumberFormatException("Non-finite numbers are not JSON scalars");
        }
        return value.matches("[-+]?\\d+") ? new BigInteger(value) : new BigDecimal(value);
    }

    private Object scalarJson() {
        if (this.graph.value().truncated()) {
            return scalarEnvelope();
        }
        return switch (this.graph.kind()) {
            case BOOLEAN -> Boolean.valueOf(this.graph.value().text());
            case NUMBER -> {
                try {
                    yield number(this.graph.value().text());
                } catch (NumberFormatException ignored) {
                    yield scalarEnvelope();
                }
            }
            case CHARACTER, STRING -> this.graph.value().text();
            default -> throw new IllegalStateException("Not a scalar execution value: " + this.graph.kind());
        };
    }

    private Map<String, Object> scalarEnvelope() {
        Map<String, Object> result = metadata();
        result.put("value", this.graph.value().text());
        addTextMetadata(result, "value", this.graph.value());
        return result;
    }

    private static void addTextMetadata(Map<String, Object> target, String name, ExecutionText text) {
        if (text.truncated()) {
            target.put(name + "_truncated", true);
            target.put(name + "_total_characters", text.totalCharacters());
        }
    }

    private static void collectReferences(ExecutionValue value, Set<Integer> identities) {
        if (value.kind() == Kind.REFERENCE) {
            identities.add(value.identity());
            return;
        }
        for (Child child : value.children()) {
            if (child.key() != null) {
                collectReferences(child.key(), identities);
            }
            collectReferences(child.value(), identities);
        }
    }

    private static String escape(String text, char quote) {
        String escaped = text.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return quote == '"' ? escaped.replace("\"", "\\\"") : escaped.replace("'", "\\'");
    }

}
