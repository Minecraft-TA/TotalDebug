package com.github.minecraft_ta.totaldebug.client.companion;

import java.nio.file.Path;
import java.util.Objects;

public record CompanionInstallation(Path companionJar) {
    public CompanionInstallation {
        companionJar = Objects.requireNonNull(companionJar, "companionJar").toAbsolutePath().normalize();
    }
}
