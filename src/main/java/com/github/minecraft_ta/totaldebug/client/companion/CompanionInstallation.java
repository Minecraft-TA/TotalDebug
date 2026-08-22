package com.github.minecraft_ta.totaldebug.client.companion;

import java.nio.file.Path;
import java.util.Objects;

public record CompanionInstallation(Path javaExecutable, Path companionJar) {
    public CompanionInstallation {
        javaExecutable = Objects.requireNonNull(javaExecutable, "javaExecutable").toAbsolutePath().normalize();
        companionJar = Objects.requireNonNull(companionJar, "companionJar").toAbsolutePath().normalize();
    }
}
