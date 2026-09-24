package com.github.minecraft_ta.totaldebug.protocol.execution;

import java.util.List;
import java.util.Objects;

/** A titled group of facts. {@code totalFacts} counts facts that were reported but not retained. */
public record FactSection(String title, List<Fact> facts, int totalFacts) {
    public static final int MAX_SECTIONS = 16;
    public static final int MAX_FACTS = 128;

    public FactSection {
        title = Objects.requireNonNullElse(title, "");
        if (title.length() > Fact.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Section title exceeds " + Fact.MAX_TEXT_LENGTH + " characters");
        }
        facts = List.copyOf(Objects.requireNonNullElse(facts, List.of()));
        if (facts.size() > MAX_FACTS || totalFacts < facts.size()) {
            throw new IllegalArgumentException("Invalid fact count for section " + title);
        }
    }

    public int omittedFacts() {
        return this.totalFacts - this.facts.size();
    }
}
