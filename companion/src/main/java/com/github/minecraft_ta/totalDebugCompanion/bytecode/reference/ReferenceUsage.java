package com.github.minecraft_ta.totalDebugCompanion.bytecode.reference;

import com.github.tth05.jindex.ReferenceKind;

import java.util.Objects;
import java.util.Set;

/** One declaration containing one or more references to the queried symbol. */
public record ReferenceUsage(
        long siteId,
        ReferenceLocation location,
        int sourceId,
        Set<ReferenceKind> kinds,
        long occurrenceCount
) implements Comparable<ReferenceUsage> {
    public ReferenceUsage {
        Objects.requireNonNull(location, "location");
        kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds"));
        if (siteId < 0) {
            throw new IllegalArgumentException("siteId must be non-negative");
        }
        if (sourceId < 0) {
            throw new IllegalArgumentException("sourceId must be non-negative");
        }
        if (kinds.isEmpty()) {
            throw new IllegalArgumentException("A usage must have at least one relation kind");
        }
        if (occurrenceCount <= 0) {
            throw new IllegalArgumentException("occurrenceCount must be positive");
        }
    }

    @Override
    public int compareTo(ReferenceUsage other) {
        int locationComparison = this.location.compareTo(other.location);
        return locationComparison != 0
                ? locationComparison
                : Long.compareUnsigned(this.siteId, other.siteId);
    }
}
