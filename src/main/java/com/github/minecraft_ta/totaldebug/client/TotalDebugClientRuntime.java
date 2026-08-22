package com.github.minecraft_ta.totaldebug.client;

import com.github.minecraft_ta.totaldebug.client.companion.CompanionAppClient;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

public final class TotalDebugClientRuntime {
    private static final Path TOTAL_DEBUG_DIRECTORY = Minecraft.getInstance()
            .gameDirectory
            .toPath()
            .resolve("total-debug")
            .toAbsolutePath()
            .normalize();
    private static final CompanionAppClient COMPANION_APP = new CompanionAppClient(TOTAL_DEBUG_DIRECTORY);
    private static final ClientDecompilationService DECOMPILATION = new ClientDecompilationService(COMPANION_APP);

    static {
        COMPANION_APP.setDecompileRequestHandler(message -> DECOMPILATION.openNamedClass(
                message.name(),
                message.targetType(),
                message.targetIdentifier()
        ));
    }

    private TotalDebugClientRuntime() {
    }

    public static ClientDecompilationService decompilation() {
        return DECOMPILATION;
    }
}
