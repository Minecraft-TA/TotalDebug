package com.github.minecraft_ta.totaldebug.decompiler.naming;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class JadLikeNameGenerator {
    private static final Map<String, String> BASE_NAMES = Map.ofEntries(
            Map.entry("byte", "b"),
            Map.entry("char", "c"),
            Map.entry("short", "short"),
            Map.entry("boolean", "flag"),
            Map.entry("double", "d"),
            Map.entry("float", "f"),
            Map.entry("String", "s"),
            Map.entry("Class", "oclass"),
            Map.entry("Long", "olong"),
            Map.entry("Byte", "obyte"),
            Map.entry("Short", "oshort"),
            Map.entry("Boolean", "obool"),
            Map.entry("Package", "opackage"),
            Map.entry("Enum", "oenum")
    );

    private final Map<String, Integer> counters = new HashMap<>();
    private final Set<String> usedNames = new HashSet<>();
    private int integerNameIndex;

    void reserve(Iterable<String> names) {
        names.forEach(this.usedNames::add);
    }

    String next(String displayedType) {
        String type = cleanType(displayedType);
        if ("int".equals(type) || "long".equals(type)) {
            return nextIntegerName();
        }

        String baseName = baseName(type);
        int suffix = this.counters.getOrDefault(baseName, 0);
        String candidate;
        do {
            candidate = suffix == 0 ? baseName : baseName + suffix;
            suffix++;
        } while (!this.usedNames.add(candidate));
        this.counters.put(baseName, suffix);
        return candidate;
    }

    void inherit(JadLikeNameGenerator parent) {
        this.integerNameIndex = Math.max(this.integerNameIndex, parent.integerNameIndex);
        parent.counters.forEach((name, value) -> this.counters.merge(name, value, Math::max));
        this.usedNames.addAll(parent.usedNames);
    }

    private String nextIntegerName() {
        String[] firstNames = {"i", "j", "k", "l"};
        String candidate;
        do {
            int index = this.integerNameIndex++;
            candidate = index < firstNames.length ? firstNames[index] : "i" + (index - firstNames.length + 1);
        } while (!this.usedNames.add(candidate));
        return candidate;
    }

    private static String baseName(String type) {
        String known = BASE_NAMES.get(type);
        if (known != null) {
            return known;
        }
        if (type.endsWith("[]")) {
            return "a" + identifierFromType(type.substring(0, type.length() - 2));
        }

        return identifierFromType(type);
    }

    private static String identifierFromType(String type) {
        String candidate = type.replace(".", "").toLowerCase(Locale.ROOT);
        StringBuilder valid = new StringBuilder(candidate.length());
        for (int index = 0; index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            if ((index == 0 && Character.isJavaIdentifierStart(character))
                    || (index > 0 && Character.isJavaIdentifierPart(character))) {
                valid.append(character);
            }
        }
        return valid.isEmpty() ? "obj" : valid.toString();
    }

    private static String cleanType(String type) {
        int genericStart = type.indexOf('<');
        if (genericStart >= 0) {
            type = type.substring(0, genericStart);
        }
        type = type.replace("...", "[]");
        StringBuilder arraySuffix = new StringBuilder();
        while (type.endsWith("[]")) {
            type = type.substring(0, type.length() - 2);
            arraySuffix.append("[]");
        }
        int packageSeparator = type.lastIndexOf('/');
        if (packageSeparator >= 0) {
            type = type.substring(packageSeparator + 1);
        }
        packageSeparator = type.lastIndexOf('.');
        if (packageSeparator >= 0) {
            type = type.substring(packageSeparator + 1);
        }
        return type + arraySuffix;
    }
}
