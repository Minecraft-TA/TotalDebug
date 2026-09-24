package com.github.minecraft_ta.totaldebug.protocol.execution;

import java.util.List;
import java.util.Objects;

/**
 * One piece of structured information reported by a script. {@code kind} decides which fields are meaningful:
 * <ul>
 *     <li>{@code TEXT}: {@code label} and {@code value}. It may have {@code children}, forming a tree such as NBT;
 *     {@code totalChildren} counts children that were reported but not retained.</li>
 *     <li>{@code BAR}: {@code amount} of {@code capacity} in {@code unit}, such as stored energy.</li>
 *     <li>{@code STACK}: {@code amount} items of registry {@code id} named {@code value}; an empty slot has an empty
 *     id.</li>
 *     <li>{@code FLUID}: {@code amount} of {@code capacity} millibuckets of fluid {@code id} named {@code value}.</li>
 *     <li>{@code PROBLEM}: reading {@code label} failed with the message {@code value}; facts reported before the
 *     failure remain.</li>
 * </ul>
 */
public record Fact(
        Kind kind,
        String label,
        String value,
        String id,
        long amount,
        long capacity,
        String unit,
        List<Fact> children,
        int totalChildren
) {
    public static final int MAX_TEXT_LENGTH = 256;

    public enum Kind {
        TEXT,
        BAR,
        STACK,
        FLUID,
        PROBLEM
    }

    public Fact {
        Objects.requireNonNull(kind, "kind");
        label = bounded(label, "label");
        value = bounded(value, "value");
        id = bounded(id, "id");
        unit = bounded(unit, "unit");
        if (amount < 0 || capacity < 0) {
            throw new IllegalArgumentException("Fact amounts must not be negative");
        }
        children = List.copyOf(Objects.requireNonNullElse(children, List.of()));
        if (totalChildren < children.size()) {
            throw new IllegalArgumentException("totalChildren must cover the retained children");
        }
        if (kind != Kind.TEXT && totalChildren > 0) {
            throw new IllegalArgumentException("Only text facts have children");
        }
    }

    public Fact(Kind kind, String label, String value, String id, long amount, long capacity, String unit) {
        this(kind, label, value, id, amount, capacity, unit, List.of(), 0);
    }

    public static Fact text(String label, String value) {
        return new Fact(Kind.TEXT, label, value, "", 0, 0, "");
    }

    /** A text fact with nested facts, of which {@code total} were reported. */
    public static Fact tree(String label, String value, List<Fact> children, int total) {
        return new Fact(Kind.TEXT, label, value, "", 0, 0, "", children, total);
    }

    public static Fact bar(String label, long amount, long capacity, String unit) {
        return new Fact(Kind.BAR, label, "", "", amount, capacity, unit);
    }

    public static Fact stack(String label, String itemId, long count, String name) {
        return new Fact(Kind.STACK, label, name, itemId, count, 0, "");
    }

    public static Fact fluid(String label, String fluidId, long amount, long capacity, String name) {
        return new Fact(Kind.FLUID, label, name, fluidId, amount, capacity, "mB");
    }

    public static Fact problem(String label, String message) {
        return new Fact(Kind.PROBLEM, label, message, "", 0, 0, "");
    }

    public int omittedChildren() {
        return this.totalChildren - this.children.size();
    }

    /** Counts this fact and all retained descendants. */
    public int nodeCount() {
        int count = 1;
        for (Fact child : this.children) {
            count += child.nodeCount();
        }
        return count;
    }

    /** The deepest chain of retained children, where a leaf has depth 1. */
    public int depth() {
        int deepest = 0;
        for (Fact child : this.children) {
            deepest = Math.max(deepest, child.depth());
        }
        return deepest + 1;
    }

    /** Shortens text to the transport limit; decoding rejects anything longer. */
    public static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH - 1) + "…";
    }

    private static String bounded(String text, String name) {
        String value = Objects.requireNonNullElse(text, "");
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Fact " + name + " exceeds " + MAX_TEXT_LENGTH + " characters");
        }
        return value;
    }
}
