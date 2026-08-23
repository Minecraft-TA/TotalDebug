package com.github.minecraft_ta.totaldebug.client.companion;

final class CompanionLaunchContract {
    static final String APP_HOME_PROPERTY = "totaldebug.companionAppHome";
    static final String APP_HOME_ARGUMENT = "--app-home";
    static final String INSTANCE_DESCRIPTOR_FILE_NAME = "instance.properties";
    static final String INSTANCE_KEY_FILE_NAME = "instance.key";
    static final String INSTANCE_LOCK_FILE_NAME = "instance.lock";
    static final String PROFILE_FILE_NAME = "profile.properties";
    static final String DESCRIPTOR_PROTOCOL_KEY = "protocol";
    static final String DESCRIPTOR_PORT_KEY = "port";
    static final String DESCRIPTOR_PROCESS_ID_KEY = "pid";
    static final String IPV4_LOOPBACK_HOST = "127.0.0.1";

    private CompanionLaunchContract() {
    }
}
