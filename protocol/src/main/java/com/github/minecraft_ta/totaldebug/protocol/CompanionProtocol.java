package com.github.minecraft_ta.totaldebug.protocol;

public final class CompanionProtocol {
    public static final int VERSION = 13;

    public static final short READY = 1;
    public static final short OPEN_CLASS = 2;
    public static final short RUN_SCRIPT = 8;
    public static final short EXECUTION_RESULT = 9;
    public static final short STOP_SCRIPT = 10;
    public static final short FOCUS_WINDOW = 11;
    public static final short CLIENT_HELLO = 21;
    public static final short SERVER_HELLO = 22;
    public static final short RUNTIME_INVENTORY = 23;
    public static final short RETRY_RUNTIME_INVENTORY = 24;
    public static final short DEBUG_TARGET = 25;

    // 26 and 27 belong to programmable-object messages.
    public static final short SERVER_MANIFEST = 28;

    private CompanionProtocol() {
    }
}
