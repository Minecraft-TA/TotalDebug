package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionLaunchContractTest {
    @Test
    void pinsTheCrossProcessLaunchContract() {
        assertEquals(
                List.of(
                        "TOTALDEBUG_SESSION_TOKEN",
                        "--data-directory",
                        "--index-file",
                        "--workspace-directory",
                        "--session-descriptor",
                        "session.properties",
                        "protocol",
                        "port",
                        "pid",
                        "127.0.0.1"
                ),
                List.of(
                        CompanionLaunchContract.TOKEN_ENVIRONMENT_VARIABLE,
                        CompanionLaunchContract.DATA_DIRECTORY_ARGUMENT,
                        CompanionLaunchContract.INDEX_FILE_ARGUMENT,
                        CompanionLaunchContract.WORKSPACE_DIRECTORY_ARGUMENT,
                        CompanionLaunchContract.SESSION_DESCRIPTOR_ARGUMENT,
                        CompanionLaunchContract.SESSION_DESCRIPTOR_FILE_NAME,
                        CompanionLaunchContract.DESCRIPTOR_PROTOCOL_KEY,
                        CompanionLaunchContract.DESCRIPTOR_PORT_KEY,
                        CompanionLaunchContract.DESCRIPTOR_PROCESS_ID_KEY,
                        CompanionLaunchContract.IPV4_LOOPBACK_HOST
                )
        );
    }
}
