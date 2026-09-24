package com.github.minecraft_ta.totaldebug.protocol.execution;

import java.util.Objects;

/**
 * One piece of structured information reported by a script. {@code kind} decides which fields are meaningful:
 * <ul>
 *     <li>{@code TEXT}: {@code label} and {@code value}.</li>
 *     <li>{@code BAR}: {@code amount} of {@code capacity} in {@code unit}, such as stored energy.</li>
 *     <li>{@code STACK}: {@code amount} items of registry {@code id} named {@code value}; an empty slot has an empty
 *     id.</li>
 *     <li>{@code FLUID}: {@code amount} of {@code capacity} millibuckets of fluid {@code id} named {@code value}.</li>
 * </ul>
 */
public record Fact(Kind kind, String label, String value, String id, long amount, long capacity, String unit) {
    public static final int MAX_TEXT_LENGTH = 256;

    public enum Kind {
        TEXT,
        BAR,
        STACK,
        FLUID
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
    }

    public static Fact text(String label, String value) {
        return new Fact(Kind.TEXT, label, value, "", 0, 0, "");
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
