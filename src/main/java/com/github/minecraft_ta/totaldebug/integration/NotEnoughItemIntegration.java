package com.github.minecraft_ta.totaldebug.integration;

public class NotEnoughItemIntegration extends TotalDebugIntegration {

    private static final NotEnoughItemIntegration INSTANCE = new NotEnoughItemIntegration();

    public static NotEnoughItemIntegration getInstance() {
        return INSTANCE;
    }
    @Override
    protected String getName() {
        return "NotEnoughItems";
    }

}
