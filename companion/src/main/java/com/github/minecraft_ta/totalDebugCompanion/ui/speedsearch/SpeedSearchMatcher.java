package com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Case-insensitive ordered fragment matching used by speed search. */
final class SpeedSearchMatcher {
    private SpeedSearchMatcher() {
    }

    static List<SpeedSearch.MatchRange> match(String candidate, String query) {
        if (candidate == null || query == null || query.isEmpty()) {
            return List.of();
        }

        String normalizedQuery = query.strip().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }

        String normalizedCandidate = candidate.toLowerCase(Locale.ROOT);
        List<SpeedSearch.MatchRange> ranges = new ArrayList<>();
        int from = 0;
        for (String fragment : normalizedQuery.split("\\s+")) {
            int found = normalizedCandidate.indexOf(fragment, from);
            if (found < 0) {
                return List.of();
            }
            int end = found + fragment.length();
            ranges.add(new SpeedSearch.MatchRange(found, end));
            from = end;
        }
        return List.copyOf(ranges);
    }
}
