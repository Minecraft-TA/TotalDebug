package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totaldebug.storage.AppPaths;
import com.github.minecraft_ta.totaldebug.storage.CompanionLaunchContract;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public record CompanionLaunchConfiguration(Path appHome) {
    public CompanionLaunchConfiguration {
        appHome = new AppPaths(appHome).home();
    }

    public static CompanionLaunchConfiguration parse(String[] arguments, Map<String, String> environment) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(environment, "environment");
        if (arguments.length == 0) {
            return new CompanionLaunchConfiguration(AppPaths.defaults(environment).home());
        }
        if (arguments.length != 2 || !CompanionLaunchContract.APP_HOME_ARGUMENT.equals(arguments[0])) {
            throw new IllegalArgumentException("Expected no arguments or --app-home <path>");
        }
        return new CompanionLaunchConfiguration(Path.of(arguments[1]));
    }

    public AppPaths paths() { return new AppPaths(appHome); }
    public Path descriptorFile() { return paths().instanceDescriptor(); }
    public Path keyFile() { return paths().instanceKey(); }
    public Path lockFile() { return paths().instanceLock(); }
    public Path profileFile() { return paths().profile(); }
}
