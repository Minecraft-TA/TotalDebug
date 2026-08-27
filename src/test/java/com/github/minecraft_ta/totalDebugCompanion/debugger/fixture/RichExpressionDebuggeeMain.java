package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

public final class RichExpressionDebuggeeMain {
    private static final String STATIC_SECRET = "static-secret";

    private RichExpressionDebuggeeMain() {
    }

    private static String staticCall() {
        return STATIC_SECRET;
    }

    public static void main(String[] args) {
        Child target = new Child();
        Integer warmedBoxingType = 0;
        int local = 2; // DEBUG_RICH_EXPRESSION
        System.out.println(target.value(local));
    }

    private static class Base {
        private final String inheritedSecret = "inherited-secret";

        private String privateBaseCall() {
            return inheritedSecret;
        }

        String virtualCall() {
            return "base";
        }
    }

    private static final class Child extends Base {
        private final String ownSecret = "own-secret";
        private static final int STATIC_VALUE = 9;

        private String value(int input) {
            return ownSecret + input;
        }

        @Override
        String virtualCall() {
            return "child";
        }

        private String overload(int input) {
            return "int";
        }

        private String overload(long input) {
            return "long";
        }

        private String overload(String input) {
            return "string";
        }

        private String boxed(Integer input) {
            return "boxed" + input;
        }

        private String varargs(String... values) {
            return String.join(",", values);
        }

        private String throwing() {
            throw new IllegalStateException("rich-expression-target-failure");
        }
    }
}
