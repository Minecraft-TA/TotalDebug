package com.github.minecraft_ta.totaldebug.integration;

public class TotalPerformanceIntegration extends TotalDebugIntegration {

    private static final TotalPerformanceIntegration INSTANCE = new TotalPerformanceIntegration();

    public static TotalPerformanceIntegration getInstance() {
        return INSTANCE;
    }
    @Override
    protected String getName() {
        return "TotalPerformance";
    }
}
