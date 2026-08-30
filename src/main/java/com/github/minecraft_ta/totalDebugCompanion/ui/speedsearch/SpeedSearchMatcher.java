package com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Case-insensitive substring, word-prefix, and subsequence matching used by speed search. */
final class SpeedSearchMatcher {
    private SpeedSearchMatcher() {
    }

    static List<SpeedSearch.MatchRange> match(String candidate, String query) {
        if (candidate == null || query == null || query.isEmpty()) {
            return List.of();
        }

        String normalizedCandidate = candidate.toLowerCase(Locale.ROOT);
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        int substring = normalizedCandidate.indexOf(normalizedQuery);
        if (substring >= 0) {
            return List.of(new SpeedSearch.MatchRange(substring, substring + query.length()));
        }

        List<Integer> wordMatch = matchWordStarts(candidate, normalizedCandidate, normalizedQuery);
        if (wordMatch != null) {
            return ranges(wordMatch);
        }

        List<Integer> subsequence = matchSubsequence(normalizedCandidate, normalizedQuery);
        return subsequence == null ? List.of() : ranges(subsequence);
    }

    private static List<Integer> matchWordStarts(
            String candidate,
            String normalizedCandidate,
            String normalizedQuery
    ) {
        List<Integer> indices = new ArrayList<>(normalizedQuery.length());
        int from = 0;
        for (int queryIndex = 0; queryIndex < normalizedQuery.length(); queryIndex++) {
            char expected = normalizedQuery.charAt(queryIndex);
            int found = -1;
            for (int candidateIndex = from; candidateIndex < normalizedCandidate.length(); candidateIndex++) {
                if (normalizedCandidate.charAt(candidateIndex) == expected
                        && isWordStart(candidate, candidateIndex)) {
                    found = candidateIndex;
                    break;
                }
            }
            if (found < 0) {
                return null;
            }
            indices.add(found);
            from = found + 1;
        }
        return indices;
    }

    private static List<Integer> matchSubsequence(String candidate, String query) {
        List<Integer> indices = new ArrayList<>(query.length());
        int from = 0;
        for (int queryIndex = 0; queryIndex < query.length(); queryIndex++) {
            int found = candidate.indexOf(query.charAt(queryIndex), from);
            if (found < 0) {
                return null;
            }
            indices.add(found);
            from = found + 1;
        }
        return indices;
    }

    private static boolean isWordStart(String value, int index) {
        if (index == 0) {
            return true;
        }
        char previous = value.charAt(index - 1);
        char current = value.charAt(index);
        return !Character.isLetterOrDigit(previous)
                || previous == '_'
                || Character.isLowerCase(previous) && Character.isUpperCase(current);
    }

    private static List<SpeedSearch.MatchRange> ranges(List<Integer> indices) {
        if (indices.isEmpty()) {
            return List.of();
        }
        List<SpeedSearch.MatchRange> ranges = new ArrayList<>();
        int start = indices.getFirst();
        int previous = start;
        for (int index = 1; index < indices.size(); index++) {
            int current = indices.get(index);
            if (current != previous + 1) {
                ranges.add(new SpeedSearch.MatchRange(start, previous + 1));
                start = current;
            }
            previous = current;
        }
        ranges.add(new SpeedSearch.MatchRange(start, previous + 1));
        return List.copyOf(ranges);
    }
}
