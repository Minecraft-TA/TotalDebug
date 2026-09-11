package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public record CompanionProfile(
        String id,
        Path dataDirectory,
        Path workspaceDirectory
) {
    public CompanionProfile {
        if (Objects.requireNonNull(id, "id").isBlank()) {
            throw new IllegalArgumentException("Profile id is blank");
        }
        dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
        workspaceDirectory = Objects.requireNonNull(workspaceDirectory, "workspaceDirectory").toAbsolutePath().normalize();
    }

    public static CompanionProfile fromHello(ClientHelloMessage hello) {
        return new CompanionProfile(hello.profileId(), Path.of(hello.dataDirectory()),
                Path.of(hello.workspaceDirectory()));
    }

    public static CompanionProfile forGame(Path directory) {
        return new CompanionProfile(com.github.minecraft_ta.totaldebug.storage.InstancePaths.profileId(directory),
                com.github.minecraft_ta.totaldebug.storage.InstancePaths.forGame(directory).home(), directory);
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("format", 1);
        json.addProperty("id", id);
        json.addProperty("instanceHome", dataDirectory.toString());
        json.addProperty("gameDirectory", workspaceDirectory.toString());
        return json;
    }

    public static CompanionProfile read(Path profileFile) throws IOException {
        try {
            JsonObject json = JsonFiles.read(profileFile);
            return fromJson(json);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Companion profile " + profileFile + ": " + exception.getMessage(), exception);
        }
    }

    static CompanionProfile fromJson(JsonObject json) {
        if (JsonFiles.integer(json, "format") != 1) throw new IllegalArgumentException("Unsupported profile format");
        return new CompanionProfile(JsonFiles.string(json, "id"),
                Path.of(JsonFiles.string(json, "instanceHome")), Path.of(JsonFiles.string(json, "gameDirectory")));
    }
}
