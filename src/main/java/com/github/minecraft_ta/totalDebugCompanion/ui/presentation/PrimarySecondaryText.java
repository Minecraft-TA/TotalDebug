package com.github.minecraft_ta.totalDebugCompanion.ui.presentation;

import java.util.Objects;

/** Theme-independent text carried by rows that show a main label and supporting context. */
public record PrimarySecondaryText(String primary, String secondary) {
    public PrimarySecondaryText {
        primary = Objects.requireNonNullElse(primary, "");
        secondary = Objects.requireNonNullElse(secondary, "");
    }

    public static PrimarySecondaryText primary(String primary) {
        return new PrimarySecondaryText(primary, "");
    }
}
