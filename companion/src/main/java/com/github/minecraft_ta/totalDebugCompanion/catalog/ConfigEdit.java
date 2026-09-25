package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what was typed for a setting into the value written to its file, checked the way NeoForge checks it on load,
 * and writes it. A value NeoForge would reject is refused here, because NeoForge answers an invalid file by backing it
 * up and resetting the setting to its default.
 */
public final class ConfigEdit {
    private static final Pattern INTEGER = Pattern.compile("[+-]?(\\d[\\d_]*|0x[\\da-fA-F_]+|0o[0-7_]+|0b[01_]+)");
    private static final Pattern FLOAT = Pattern.compile("[+-]?(\\d[\\d_]*(\\.\\d[\\d_]*)?([eE][+-]?\\d[\\d_]*)?|inf|nan)");
    private static final Pattern RANGE = Pattern.compile("(\\S+) ~ (\\S+)");

    /** What a value is, from how the file writes it. */
    public enum Kind { STRING, BOOLEAN, INTEGER, FLOAT, LIST, OTHER }

    private ConfigEdit() {
    }

    public static Kind kind(String literal) {
        if (literal.startsWith("\"") || literal.startsWith("'")) return Kind.STRING;
        if (literal.equals("true") || literal.equals("false")) return Kind.BOOLEAN;
        if (literal.startsWith("[")) return Kind.LIST;
        if (INTEGER.matcher(literal).matches()) return Kind.INTEGER;
        if (FLOAT.matcher(literal).matches()) return Kind.FLOAT;
        return Kind.OTHER;
    }

    /** Whether a value written as {@code literal} can be edited. Dates and inline tables are left to the file. */
    public static boolean editable(String literal) {
        return literal != null && kind(literal) != Kind.OTHER;
    }

    /**
     * The value to write for {@code input}, typed for a setting currently written as {@code current}. Strings are typed
     * without quotes and lists as TOML arrays. {@code setting} carries the accepted range and values; null for a file
     * without a specification.
     *
     * @throws IllegalArgumentException with what the setting accepts, when it does not accept the input
     */
    public static String literal(String current, PackCatalog.ConfigSetting setting, String input) {
        String text = input.strip();
        List<String> allowed = setting == null ? List.of() : setting.allowed();
        String range = setting == null ? "" : setting.range();
        return switch (kind(current)) {
            case STRING -> {
                if (allowed.isEmpty()) yield quote(input);
                for (String choice : allowed) {
                    if (choice.equalsIgnoreCase(text)) yield quote(choice);
                }
                throw new IllegalArgumentException("Accepts " + String.join(", ", allowed));
            }
            case BOOLEAN -> {
                String lower = text.toLowerCase(Locale.ROOT);
                if (!lower.equals("true") && !lower.equals("false")) throw new IllegalArgumentException("Accepts true or false");
                yield lower;
            }
            case INTEGER -> {
                BigDecimal number = number(text);
                if (number == null || number.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("Enter a whole number");
                checkRange(number, range);
                try {
                    yield Long.toString(number.longValueExact());
                } catch (ArithmeticException tooLarge) {
                    throw new IllegalArgumentException("Enter a whole number from " + Long.MIN_VALUE + " to " + Long.MAX_VALUE);
                }
            }
            case FLOAT -> {
                BigDecimal number = number(text);
                if (number == null) throw new IllegalArgumentException("Enter a number");
                checkRange(number, range);
                // A floating-point setting must stay a float in the file; NeoForge resets a whole number in its place.
                String plain = number.toPlainString();
                yield plain.contains(".") ? plain : plain + ".0";
            }
            case LIST -> {
                Object value;
                try {
                    value = ConfigValues.value(text);
                } catch (IllegalArgumentException notToml) {
                    throw new IllegalArgumentException("Enter a TOML array, such as [\"a\", \"b\"]");
                }
                if (!(value instanceof List<?>)) throw new IllegalArgumentException("Enter a TOML array, such as [\"a\", \"b\"]");
                yield text;
            }
            case OTHER -> throw new IllegalArgumentException("Edit this value in the file");
        };
    }

    private static BigDecimal number(String text) {
        try {
            return new BigDecimal(text.replace("_", ""));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static void checkRange(BigDecimal number, String range) {
        BigDecimal minimum = null;
        BigDecimal maximum = null;
        if (range.startsWith("> ")) {
            minimum = number(range.substring(2));
        } else if (range.startsWith("< ")) {
            maximum = number(range.substring(2));
        } else {
            Matcher bounds = RANGE.matcher(range);
            if (bounds.matches()) {
                minimum = number(bounds.group(1));
                maximum = number(bounds.group(2));
            }
        }
        if (minimum != null && number.compareTo(minimum) < 0 || maximum != null && number.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Accepts " + readableRange(range));
        }
    }

    /**
     * A NeoForge range in words. Its {@code > 1} means at least 1, and a bound at the type's limit, such as
     * {@code 9223372036854775807}, is no bound at all.
     */
    public static String readableRange(String range) {
        if (range.startsWith("> ")) return "at least " + trimmed(range.substring(2));
        if (range.startsWith("< ")) return "at most " + trimmed(range.substring(2));
        Matcher bounds = RANGE.matcher(range);
        if (!bounds.matches()) return range;
        boolean noMinimum = unbounded(bounds.group(1), false);
        boolean noMaximum = unbounded(bounds.group(2), true);
        if (noMinimum && noMaximum) return "";
        if (noMaximum) return "at least " + trimmed(bounds.group(1));
        if (noMinimum) return "at most " + trimmed(bounds.group(2));
        return trimmed(bounds.group(1)) + " to " + trimmed(bounds.group(2));
    }

    private static boolean unbounded(String bound, boolean upper) {
        try {
            double value = Double.parseDouble(bound);
            return upper ? value >= Integer.MAX_VALUE : value <= Integer.MIN_VALUE;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /** Drops a floating-point value's empty fraction, so {@code 4000000.0} reads as {@code 4000000}. */
    private static String trimmed(String value) {
        return value.endsWith(".0") ? value.substring(0, value.length() - 2) : value;
    }

    /** A TOML basic string. */
    static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (character < 0x20 || character == 0x7f) quoted.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    else quoted.append(character);
                }
            }
        }
        return quoted.append('"').toString();
    }

    /**
     * Writes {@code literal} as the value of {@code key}, changing nothing else in the file, and returns the value it
     * replaced. The file is read again first, so edits made meanwhile by the game or an editor are kept.
     */
    public static String write(Path file, String key, String literal) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String name = file.getFileName().toString();
        TomlText toml;
        try {
            toml = TomlText.of(text);
        } catch (IllegalArgumentException problem) {
            throw new IOException(name + " could not be edited: " + problem.getMessage(), problem);
        }
        String previous = toml.literal(key);
        if (previous == null) throw new IOException(key + " is not in " + name);
        String updated = toml.with(key, literal);
        // The edited text must hold the same settings with only this one changed.
        Map<String, String> before = new HashMap<>(ConfigValues.parse(text, name).values());
        Map<String, String> after = new HashMap<>(ConfigValues.parse(updated, name).values());
        String written = after.remove(key);
        before.remove(key);
        if (!before.equals(after) || !Objects.equals(written, PackCatalog.ConfigSetting.display(ConfigValues.value(literal)))) {
            throw new IOException(name + " was left as it is: writing " + key + " would change other settings");
        }
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        return previous;
    }
}
