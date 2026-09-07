package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

public final class McpDebuggeeMain {
    public static void main(String[] args) throws Exception {
        int counter = 41;
        Payload payload = new Payload();
        int[] values = {3, 4, 5};
        System.out.println("ready");
        while (System.in.read() >= 0) {
            counter = increment(counter); // MCP_BREAK
            System.out.println(counter + ":" + payload.amount + ":" + values[1]);
        }
    }

    private static int increment(int value) {
        return value + 1;
    }

    private static final class Payload {
        private final int amount = 5;
        private final String label = "creeper";
    }
}
