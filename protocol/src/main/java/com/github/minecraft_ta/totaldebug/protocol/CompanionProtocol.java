package com.github.minecraft_ta.totaldebug.protocol;

public final class CompanionProtocol {
    public static final int VERSION = 40;

    public static final short READY = 1;
    // 2 was the game opening a class, which the /decompile command used.
    public static final short RUN_SCRIPT = 8;
    public static final short EXECUTION_RESULT = 9;
    public static final short STOP_SCRIPT = 10;
    public static final short FOCUS_WINDOW = 11;
    public static final short CLIENT_HELLO = 21;
    public static final short SERVER_HELLO = 22;
    // 23 was the runtime inventory's state, now a prepared file.
    public static final short RETRY_RUNTIME_INVENTORY = 24;
    public static final short DEBUG_TARGET = 25;

    // 26 and 27 belong to programmable-object messages.
    public static final short SERVER_SCRIPTS = 28;
    // 29 was a request for a server source's class fingerprints.
    public static final short INSPECT_SUBJECT = 30;
    // 31 and 32 were the item icon archive and the pack catalog, now prepared files.
    // 33 and 34 were a key binding set and its answer, now a change.
    public static final short PACK_STACK = 35;
    public static final short RELOAD = 36;
    public static final short RELOAD_RESULT = 37;
    // 38 was a pack selection, now a change.
    public static final short PLAYING = 39;
    // 40 and 41 belong to game-rule messages.
    public static final short TO_SERVER = 42;
    public static final short FROM_SERVER = 43;
    public static final short RELAY_FAILED = 44;
    public static final short SERVER_SCRIPTS_REQUEST = 45;
    public static final short COMPANION_LEFT = 46;
    public static final short PREPARED_FILE = 47;
    public static final short CHANGE = 48;
    public static final short CHANGE_RESULT = 49;
    public static final short DATAPACKS = 50;
    public static final short DATAPACKS_REQUEST = 51;

    private CompanionProtocol() {
    }
}
