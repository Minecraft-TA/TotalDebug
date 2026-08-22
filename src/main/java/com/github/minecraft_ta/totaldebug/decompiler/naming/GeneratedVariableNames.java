package com.github.minecraft_ta.totaldebug.decompiler.naming;

import java.util.regex.Pattern;

public final class GeneratedVariableNames {
    private static final Pattern PATTERN = Pattern.compile(
            "(?:p_\\d+_|var\\d+(?:_\\d+)?|arg\\d+|param\\d+|\\$\\$\\d+|☃.*)"
    );

    private GeneratedVariableNames() {
    }

    public static boolean matches(String name) {
        return name == null || name.isBlank() || PATTERN.matcher(name).matches();
    }
}
