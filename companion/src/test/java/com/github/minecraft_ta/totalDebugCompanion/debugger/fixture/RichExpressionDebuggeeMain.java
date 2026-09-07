package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.z.Blocks;

public final class RichExpressionDebuggeeMain {
    private static final String STATIC_SECRET = "static-secret";

    private RichExpressionDebuggeeMain() {
    }

    private static String staticCall() {
        return STATIC_SECRET;
    }

    public static void main(String[] args) {
        System.out.println(com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.a.Blocks.WRONG);
        System.out.println(Blocks.CORRECT);
        staticCompletionFrame();
        Child target = new Child();
        target.debugExpressions();
        target.lifecycleProbe();
        System.out.println(target.value(2));
    }

    private static void staticCompletionFrame() {
        int staticLocal = 0; // DEBUG_RICH_STATIC_COMPLETION
        System.out.println(staticLocal);
    }

    private static class Base {
        private final String inheritedSecret = "inherited-secret";

        private String privateBaseCall() {
            return inheritedSecret;
        }

        String virtualCall() {
            return "base";
        }

        String overridable() {
            return "base-overridable";
        }
    }

    private interface Defaulted {
        default String defaultCall() {
            return "default-interface";
        }
    }

    private static final class Child extends Base implements Defaulted {
        private final String ownSecret = "own-secret";
        private final String inheritedSecret = "child-hidden";
        private String nullable;
        private static final int STATIC_VALUE = 9;
        private int completionCalls;

        private String value(int input) {
            return ownSecret + input;
        }

        private void debugExpressions() {
            Child target = this;
            Base declaredTarget = this;
            String[] strings = new String[]{"a", "b"};
            Integer warmedBoxingType = 0;
            Boolean warmedBooleanType = true;
            int local = 2; // DEBUG_RICH_EXPRESSION
            System.out.println(target.value(local));
        }

        private Child sideEffect() {
            completionCalls++;
            return this;
        }

        @Override
        String virtualCall() {
            return "child";
        }

        @Override
        String overridable() {
            return "child-overridable";
        }

        private static String staticChild() {
            return "static-child";
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

        private String unboxed(int input) {
            return "unboxed" + input;
        }

        private String unboxedLong(long input) {
            return "unboxed-long" + input;
        }

        private String booleanAccepted(boolean input) {
            return input ? "boolean-true" : "boolean-false";
        }

        private String boxedLong(Long input) {
            return "long" + input;
        }

        private String nullOverload(String input) {
            return "string-null";
        }

        private String nullOverload(Object input) {
            return "object-null";
        }

        private String nullUnrelated(CharSequence input) {
            return "char-sequence-null";
        }

        private String nullUnrelated(Number input) {
            return "number-null";
        }

        private String fixed(String input) {
            return "fixed";
        }

        private String fixed(String... input) {
            return "varargs-fixed";
        }

        private String waitsForWorker() throws InterruptedException {
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            Thread worker = new Thread(done::countDown, "rich-expression-worker");
            worker.start();
            if (!done.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("worker did not run during evaluation");
            }
            worker.join();
            return "worker-complete";
        }

        private String lifecycleProbe() {
            return "lifecycle-probe"; // DEBUG_RICH_LIFECYCLE
        }

        private String neverReturns() {
            while (true) {
                Thread.onSpinWait();
            }
        }

        private String varargs(String... values) {
            return String.join(",", values);
        }

        private String varargsOrNull(String... values) {
            return values == null ? "array-null" : String.join(",", values);
        }

        private String completionOverload(int value) {
            return "not-an-object";
        }

        private Child completionOverload(String value) {
            return this;
        }

        private String throwing() {
            throw new IllegalStateException("rich-expression-target-failure");
        }
    }
}
