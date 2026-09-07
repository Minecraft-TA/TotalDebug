package com.github.minecraft_ta.totaldebug.protocol.navigation;

/** Stable source-selection codes on the Companion wire protocol. */
public final class SourceTargetKind {
    public static final int WHOLE_CLASS = -1;
    public static final int FIELD = 8;
    public static final int METHOD = 9;

    private SourceTargetKind() {
    }
}
