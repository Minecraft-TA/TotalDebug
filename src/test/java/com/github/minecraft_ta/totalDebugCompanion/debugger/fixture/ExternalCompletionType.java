package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

public class ExternalCompletionType {
    private final String privateValue = "external";

    public String externalMethod() {
        return privateValue;
    }

    public static String externalStatic() {
        return "static";
    }
}
