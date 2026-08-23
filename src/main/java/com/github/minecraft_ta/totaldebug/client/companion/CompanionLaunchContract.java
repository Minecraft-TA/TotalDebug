package com.github.minecraft_ta.totaldebug.client.companion;

final class CompanionLaunchContract {
    static final String TOKEN_ENVIRONMENT_VARIABLE = "TOTALDEBUG_SESSION_TOKEN";

    static final String DATA_DIRECTORY_ARGUMENT = "--data-directory";
    static final String INDEX_FILE_ARGUMENT = "--index-file";
    static final String WORKSPACE_DIRECTORY_ARGUMENT = "--workspace-directory";
    static final String SESSION_DESCRIPTOR_ARGUMENT = "--session-descriptor";

    static final String SESSION_DESCRIPTOR_FILE_NAME = "session.properties";
    static final String RUNTIME_SOURCE_MANIFEST_FILE_NAME = "runtime-sources.txt";
    static final String DESCRIPTOR_PROTOCOL_KEY = "protocol";
    static final String DESCRIPTOR_PORT_KEY = "port";
    static final String DESCRIPTOR_PROCESS_ID_KEY = "pid";
    static final String IPV4_LOOPBACK_HOST = "127.0.0.1";

    private CompanionLaunchContract() {
    }
}
