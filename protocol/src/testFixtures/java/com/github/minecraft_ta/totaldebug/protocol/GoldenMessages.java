package com.github.minecraft_ta.totaldebug.protocol;

/** Released protocol-11 payloads, kept independently of the encoder. */
public final class GoldenMessages {
    public static final String RUN_SCRIPT = "00000007000000117075626c696320636c6173732058207b7d0100000009504f53545f5449434b";
    public static final String STOP_SCRIPT = "00000007";
    public static final String CLIENT_HELLO = "0000000b00000003616263000000017000000001640000000177";
    public static final String SERVER_HELLO = "0000000b0100000000";
    public static final String RUNTIME_INVENTORY = "000000010000000269640000000466696c6500000000";
    public static final String DEBUG_TARGET = "0000000269640000000467616d6501000000000000002a";

    private GoldenMessages() {
    }
}
