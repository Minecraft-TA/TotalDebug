package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

public final class DebuggeeMain {
    private DebuggeeMain() {
    }

    public static void main(String[] args) {
        String message = "minecraft";
        int counter = 41;
        Payload payload = new Payload(5, "creeper");
        int[] values = {3, 4};
        counter = increment(counter); // DEBUG_BREAKPOINT
        System.out.println(message + ":" + counter + ":" + payload.label + ":" + values[1]); // DEBUG_AFTER_STEP
    }

    private static int increment(int value) {
        return value + 1;
    }

    private static final class Payload {
        private final int amount;
        private final String label;

        private Payload(int amount, String label) {
            this.amount = amount;
            this.label = label;
        }
    }
}
