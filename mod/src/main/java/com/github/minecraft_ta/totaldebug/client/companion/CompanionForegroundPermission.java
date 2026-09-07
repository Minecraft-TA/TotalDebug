package com.github.minecraft_ta.totaldebug.client.companion;

import java.io.IOException;

@FunctionalInterface
interface CompanionForegroundPermission {
    void grantTo(long processId) throws IOException;
}
