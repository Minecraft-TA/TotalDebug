package com.github.minecraft_ta.totaldebug.bytecode.reference;

import java.util.List;
import java.util.Objects;

/** A complete or cooperatively cancelled reference scan. */
public record ReferenceSearchResult(
        List<ReferenceLocation> locations,
        int scannedClassFiles,
        int totalClassFiles,
        boolean cancelled
) {
    public ReferenceSearchResult {
        locations = List.copyOf(Objects.requireNonNull(locations, "locations"));
        if (scannedClassFiles < 0 || totalClassFiles < 0 || scannedClassFiles > totalClassFiles) {
            throw new IllegalArgumentException(
                    "Invalid reference-search counts: " + scannedClassFiles + "/" + totalClassFiles
            );
        }
        if (!cancelled && scannedClassFiles != totalClassFiles) {
            throw new IllegalArgumentException("A completed search must scan every class file");
        }
    }
}
