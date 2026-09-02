package com.github.minecraft_ta.totaldebug.storage;

public final class CompanionLaunchContract {
    public static final String APP_HOME_PROPERTY = AppPaths.HOME_PROPERTY;
    public static final String APP_HOME_ARGUMENT = "--app-home";
    public static final String INSTANCE_DESCRIPTOR_FILE_NAME = "instance.properties";
    public static final String INSTANCE_KEY_FILE_NAME = "instance.key";
    public static final String INSTANCE_LOCK_FILE_NAME = "instance.lock";
    public static final String PROFILE_FILE_NAME = "profile.json";
    public static final String DESCRIPTOR_PROTOCOL_KEY = "protocol";
    public static final String DESCRIPTOR_PORT_KEY = "port";
    public static final String DESCRIPTOR_PROCESS_ID_KEY = "pid";
    public static final String IPV4_LOOPBACK_HOST = "127.0.0.1";

    private CompanionLaunchContract() {
    }
}
