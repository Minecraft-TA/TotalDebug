package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JDTHacks;
import com.github.minecraft_ta.totaldebug.storage.AppPaths;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import java.nio.file.Path;

public final class JdtTestEnvironment implements BeforeAllCallback {
    public static final AppPaths PATHS = new AppPaths(Path.of("build", "test-app", Long.toString(ProcessHandle.current().pid())).toAbsolutePath());
    private static boolean initialized;
    @Override public void beforeAll(ExtensionContext context) throws Exception {
        if (initialized) return;
        GlobalConfig.getInstance().loadFrom(PATHS.home());
        JDTHacks.init(PATHS.jdtCache());
        initialized = true;
    }
}
