package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

import com.github.minecraft_ta.totaldebug.evaluation.PausedEvaluationBridge;

import java.io.IOException;

public final class EvaluationSemanticsDebuggee {
    private static int calls;
    private static int order;

    public static void main(String[] args) {
        PausedEvaluationBridge.preload();
        EvaluationSemanticsDebuggee receiver = new EvaluationSemanticsDebuggee();
        int local = 7;
        System.out.println(local); // EVALUATION_STOP
        System.out.println("done"); // EVALUATION_SECOND
    }

    private int awaitCancellation() throws IOException {
        System.out.println("action entered");
        return System.in.read();
    }

    private EvaluationSemanticsDebuggee receiver() {
        order = order * 10 + 1;
        return this;
    }

    private int argument() {
        order = order * 10 + 2;
        return 0;
    }

    private int combine(int ignored) {
        return order;
    }

    private int touch() {
        calls++;
        return 1;
    }

    private int consume(int first, Object second) {
        return first;
    }
}
