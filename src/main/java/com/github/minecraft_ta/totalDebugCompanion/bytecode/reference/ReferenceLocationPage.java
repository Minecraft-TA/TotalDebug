package com.github.minecraft_ta.totalDebugCompanion.bytecode.reference;

import java.util.List;
import java.util.Objects;

/** A bounded page of indexed reference locations. */
public record ReferenceLocationPage(List<ReferenceLocation> locations, boolean truncated) {
    public ReferenceLocationPage {
        locations = List.copyOf(Objects.requireNonNull(locations, "locations"));
    }
}
