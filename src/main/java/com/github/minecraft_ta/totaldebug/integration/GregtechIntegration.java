package com.github.minecraft_ta.totaldebug.integration;

public class GregtechIntegration extends TotalDebugIntegration {

    private static final GregtechIntegration INSTANCE = new GregtechIntegration();

    public static GregtechIntegration getInstance() {
        return INSTANCE;
    }
    @Override
    protected String getName() {
        return "gregtech";
    }
}
