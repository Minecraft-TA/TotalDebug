package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionLaunchContractTest {
    @Test
    void pinsTheCrossProcessLaunchContract() {
        assertEquals(
                List.of(
                        "totaldebug.companionAppHome",
                        "--app-home",
                        "instance.properties",
                        "instance.key",
                        "instance.lock",
                        "profile.properties",
                        "protocol",
                        "port",
                        "pid",
                        "127.0.0.1"
                ),
                List.of(
                        CompanionLaunchContract.APP_HOME_PROPERTY,
                        CompanionLaunchContract.APP_HOME_ARGUMENT,
                        CompanionLaunchContract.INSTANCE_DESCRIPTOR_FILE_NAME,
                        CompanionLaunchContract.INSTANCE_KEY_FILE_NAME,
                        CompanionLaunchContract.INSTANCE_LOCK_FILE_NAME,
                        CompanionLaunchContract.PROFILE_FILE_NAME,
                        CompanionLaunchContract.DESCRIPTOR_PROTOCOL_KEY,
                        CompanionLaunchContract.DESCRIPTOR_PORT_KEY,
                        CompanionLaunchContract.DESCRIPTOR_PROCESS_ID_KEY,
                        CompanionLaunchContract.IPV4_LOOPBACK_HOST
                )
        );
    }
}
