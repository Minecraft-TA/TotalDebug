package com.github.minecraft_ta.totaldebug.client.companion;

public final class CompanionProtocol {
    public static final int VERSION = 10;

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

    public static final long CAPABILITY_CODE_VIEW = 1L;
    public static final long CAPABILITY_FOCUS_WINDOW = 1L << 1;
    public static final long CAPABILITY_SCRIPT_EXECUTION = 1L << 4;
    public static final long CAPABILITY_RUNTIME_INVENTORY = 1L << 7;
    public static final long CAPABILITY_DEBUGGER = 1L << 8;

    public static final long CORE_CAPABILITIES = CAPABILITY_CODE_VIEW
            | CAPABILITY_FOCUS_WINDOW
            | CAPABILITY_RUNTIME_INVENTORY;
    public static final long REQUESTED_CAPABILITIES = CORE_CAPABILITIES
            | CAPABILITY_SCRIPT_EXECUTION
            | CAPABILITY_DEBUGGER;

    private CompanionProtocol() {
    }
}
