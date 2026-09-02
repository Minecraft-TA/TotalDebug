package com.github.minecraft_ta.totaldebug.storage;

import java.util.Locale;
import java.util.Set;

/** Readable, portable file stems. The owning manifest records any shortened or disambiguated names. */
public final class CacheNames {
    private CacheNames() {
    }

    public static String uniqueStem(String label, Set<String> usedNames) {
        String stem = label.replaceAll("[^a-zA-Z0-9._$-]", "_");
        if (stem.length() > 120) {
            stem = stem.substring(0, 80) + "_" + stem.substring(stem.length() - 39);
        }
        stem = stem.replaceAll("[. ]+$", "");
        if (stem.isEmpty() || stem.startsWith(".") || stem.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
            stem = "_" + stem;
        }
        String candidate = stem;
        for (int suffix = 2; !usedNames.add(candidate.toLowerCase(Locale.ROOT)); suffix++) {
            candidate = stem + "-" + suffix;
        }
        return candidate;
    }

    public static String requireFileName(String name) {
        if (name == null || !name.matches("[a-zA-Z0-9_$-][a-zA-Z0-9._$-]*") || name.endsWith(".")) {
            throw new IllegalArgumentException("Invalid cache file name: " + name);
        }
        return name;
    }
}
