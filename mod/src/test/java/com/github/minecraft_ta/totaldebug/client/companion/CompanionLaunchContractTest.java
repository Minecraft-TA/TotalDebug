package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionLaunchContractTest {
    @Test
    void launchesCompanionWithItsAppHome() {
        Path javaExecutable = Path.of("runtime", "bin", "java.exe");
        Path companionJar = Path.of("companion", "TotalDebugCompanion.jar");
        Path appHome = Path.of("total-debug", "companion-app");

        assertEquals(
                List.of(
                        javaExecutable.toString(),
                        "-jar",
                        companionJar.toString(),
                        "--app-home",
                        appHome.toString()
                ),
                CompanionAppClient.buildLaunchCommand(javaExecutable, companionJar, appHome)
        );
    }
}
