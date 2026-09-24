package com.github.minecraft_ta.totaldebug.protocol.execution;

import java.util.List;
import java.util.Objects;

/**
 * A titled group of facts. {@code totalFacts} counts top-level facts that were reported but not retained. Nested facts
 * share one node budget per section.
 */
public record FactSection(String title, List<Fact> facts, int totalFacts) {
    public static final int MAX_SECTIONS = 16;
    public static final int MAX_FACTS = 128;
    public static final int MAX_NODES = 1_000;
    public static final int MAX_DEPTH = 16;

    public FactSection {
        title = Objects.requireNonNullElse(title, "");
        if (title.length() > Fact.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Section title exceeds " + Fact.MAX_TEXT_LENGTH + " characters");
        }
        facts = List.copyOf(Objects.requireNonNullElse(facts, List.of()));
        if (facts.size() > MAX_FACTS || totalFacts < facts.size()) {
            throw new IllegalArgumentException("Invalid fact count for section " + title);
        }
        int nodes = 0;
        for (Fact fact : facts) {
            nodes += fact.nodeCount();
            if (fact.depth() > MAX_DEPTH) {
                throw new IllegalArgumentException("Facts in section " + title + " nest deeper than " + MAX_DEPTH);
            }
        }
        if (nodes > MAX_NODES) {
            throw new IllegalArgumentException("Section " + title + " exceeds " + MAX_NODES + " facts");
        }
    }

    public int omittedFacts() {
        return this.totalFacts - this.facts.size();
    }
}
