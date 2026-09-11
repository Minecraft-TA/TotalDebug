package com.github.minecraft_ta.totaldebug.protocol;

/** Protocol-14 payloads, kept independently of the encoder. */
public final class GoldenMessages {
    public static final String RUN_SCRIPT = "0000000700000001580000000100000001580000000301020300000009696e76656e746f72790100000009504f53545f5449434b0000000173";
    public static final String STOP_SCRIPT = "00000007";
    public static final String CLIENT_HELLO = "0000000e00000003616263000000017000000001640000000177";
    public static final String SERVER_HELLO = "0000000e0100000000";
    public static final String RUNTIME_INVENTORY = "000000010000000269640000000466696c6500000000";
    public static final String DEBUG_TARGET = "0000000269640000000467616d6501000000000000002a";

    private GoldenMessages() {
    }
}
