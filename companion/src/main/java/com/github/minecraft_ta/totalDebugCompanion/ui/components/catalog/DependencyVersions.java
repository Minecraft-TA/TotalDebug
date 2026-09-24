package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Human-readable Maven version restrictions without changing their inclusive/exclusive bounds. */
final class DependencyVersions {
    private static final Pattern RANGE = Pattern.compile("([\\[(])([^\\[\\]()]*)([\\])])");

    private DependencyVersions() { }

    static String describe(String range) {
        if (range.isBlank()) return "Any version";
        if (!range.startsWith("[") && !range.startsWith("(")) return range + " preferred";
        var matcher = RANGE.matcher(range);
        List<String> alternatives = new ArrayList<>();
        int end = 0;
        while (matcher.find()) {
            if (!range.substring(end, matcher.start()).strip().equals(end == 0 ? "" : ",")) return range;
            String[] bounds = matcher.group(2).split(",", -1);
            boolean lowerInclusive = matcher.group(1).equals("[");
            boolean upperInclusive = matcher.group(3).equals("]");
            if (bounds.length == 1 && lowerInclusive && upperInclusive) {
                alternatives.add(bounds[0].strip());
            } else if (bounds.length == 2) {
                String lower = bounds[0].strip();
                String upper = bounds[1].strip();
                if (lower.isEmpty() && upper.isEmpty()) alternatives.add("Any version");
                else if (lower.isEmpty()) alternatives.add(upperInclusive ? upper + " or older" : "Earlier than " + upper);
                else if (upper.isEmpty()) alternatives.add(lowerInclusive ? lower + " or newer" : "Later than " + lower);
                else if (lowerInclusive && upperInclusive) alternatives.add(lower.equals(upper) ? lower : lower + " through " + upper);
                else alternatives.add((lowerInclusive ? "At least " : "Later than ") + lower
                        + (upperInclusive ? ", up to " : ", before ") + upper);
            } else return range;
            end = matcher.end();
        }
        return end == range.length() && !alternatives.isEmpty() ? String.join(" or ", alternatives) : range;
    }
}
