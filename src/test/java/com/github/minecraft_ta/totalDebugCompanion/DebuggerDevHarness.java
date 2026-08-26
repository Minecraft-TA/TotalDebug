package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.debugger.harness.DebuggerScenarios;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the debugger proof suite without the Companion UI or JUnit.
 *
 * <pre>
 * ./gradlew debuggerHarness
 * ./gradlew debuggerHarness --args="--scenario=stepping"
 * ./gradlew debuggerHarness --args="--list"
 * </pre>
 */
public final class DebuggerDevHarness {
    private DebuggerDevHarness() {
    }

    public static void main(String[] args) throws Exception {
        Logger rootLogger = Logger.getLogger("");
        rootLogger.setLevel(Level.WARNING);
        for (Handler handler : rootLogger.getHandlers()) {
            handler.setLevel(Level.WARNING);
        }
        List<DebuggerScenarios.Scenario> scenarios = selectScenarios(args);
        if (scenarios.isEmpty()) {
            return;
        }
        System.out.println("Debugger harness: real Java 21 child JVMs, real JDWP, no UI");
        Instant suiteStart = Instant.now();
        int completed = 0;
        for (DebuggerScenarios.Scenario scenario : scenarios) {
            Instant scenarioStart = Instant.now();
            System.out.println("  RUN   " + scenario.name());
            try {
                scenario.run();
                completed++;
                System.out.println("  PASS  " + scenario.name() + " (" + elapsed(scenarioStart) + ")");
            } catch (Throwable failure) {
                System.out.println("  FAIL  " + scenario.name() + " (" + elapsed(scenarioStart) + ")");
                throw failure;
            }
        }
        System.out.println("Passed " + completed + " debugger scenarios in " + elapsed(suiteStart));
    }

    private static List<DebuggerScenarios.Scenario> selectScenarios(String[] args) {
        if (Arrays.asList(args).contains("--list")) {
            System.out.println("Debugger scenarios:");
            for (DebuggerScenarios.Scenario scenario : DebuggerScenarios.all()) {
                System.out.println("  " + scenario.id() + "  " + scenario.name());
            }
            return List.of();
        }
        String selectedId = Arrays.stream(args)
                .filter(argument -> argument.startsWith("--scenario="))
                .map(argument -> argument.substring("--scenario=".length()))
                .findFirst()
                .orElse(null);
        if (selectedId == null) {
            return DebuggerScenarios.all();
        }
        return List.of(DebuggerScenarios.all().stream()
                .filter(scenario -> scenario.id().equals(selectedId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown debugger scenario '" + selectedId + "'; run with --list"
                )));
    }

    private static String elapsed(Instant start) {
        return Duration.between(start, Instant.now()).toMillis() + " ms";
    }
}
