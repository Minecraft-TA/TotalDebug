package com.github.minecraft_ta.totalDebugCompanion.resource;

import javax.swing.Icon;

public record ResourceFileType(Kind kind, String syntaxStyle, Icon icon, String description) {

    public enum Kind {
        TEXT,
        PNG,
        CLASS,
        BINARY,
        UNKNOWN
    }
}
