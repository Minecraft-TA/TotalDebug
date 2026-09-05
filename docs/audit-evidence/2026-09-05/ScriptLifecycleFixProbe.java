package com.github.minecraft_ta.totaldebug.client.script;

import com.github.minecraft_ta.totaldebug.script.*;
import com.github.minecraft_ta.totaldebug.tick.TickTaskScheduler;
import com.github.minecraft_ta.totaldebug.network.RunServerScriptPayload;
import com.github.minecraft_ta.totaldebug.network.StopServerScriptPayload;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;

/** Isolated deterministic lock-order probe. No Minecraft connection or persistent user state. */
public class ScriptLifecycleFixProbe {
    public static void main(String[] args) throws Exception {
        CountDownLatch terminalDelivered = new CountDownLatch(1);
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch serviceLocked = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        var service = new ClientScriptService((id, result) -> {}, new TickTaskScheduler(), new ServerScriptTransport() {
            public Availability availability() { return Availability.unsupported("audit"); }
            public void run(RunServerScriptPayload value) {}
            public void stop(StopServerScriptPayload value) {}
        });
        Class<?> sideType = Class.forName(ClientScriptService.class.getName() + "$ExecutionSide");
        Object clientSide = sideType.getEnumConstants()[0];
        Method accept = ClientScriptService.class.getDeclaredMethod("acceptResult", int.class, ExecutionResult.class, sideType);
        accept.setAccessible(true);
        ExecutionResultSink sink = (id, result) -> {
            try {
                sinkEntered.countDown();
                awaitRelease(releaseSink);
                accept.invoke(service, id, result, clientSide);
                if (result.status() != ExecutionStatus.COMPILATION_COMPLETED) terminalDelivered.countDown();
            } catch (Exception exception) { throw new RuntimeException(exception); }
        };
        Method factory = Arrays.stream(ScriptRunnerTest.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("runner")).findFirst().orElseThrow();
        factory.setAccessible(true);
        ScriptRunner runner = (ScriptRunner) factory.invoke(null, (ScriptTickScheduler) (phase, task) -> {}, sink, Duration.ofMillis(30));
        Field runnerField = ClientScriptService.class.getDeclaredField("runner");
        runnerField.setAccessible(true);
        runnerField.set(service, runner);
        Field activeField = ClientScriptService.class.getDeclaredField("activeRuns");
        activeField.setAccessible(true);
        ((Map<Integer, Object>) activeField.get(service)).put(41, clientSide);
        runner.runScript(41, "public class AuditRun extends com.github.minecraft_ta.totaldebug.script.ScriptProgram { public Object run() { return null; } }", ScriptExecutionEnvironment.POST_TICK);
        if (!sinkEntered.await(15, TimeUnit.SECONDS)) throw new AssertionError("No compiler status");
        Thread stop = new Thread(() -> {
            synchronized (service) {
                serviceLocked.countDown();
                service.stopScript(41);
            }
        }, "audit-script-stop");
        stop.setDaemon(true);
        stop.start();
        if (!serviceLocked.await(5, TimeUnit.SECONDS)) throw new AssertionError("No stop monitor");
        releaseSink.countDown();
        stop.join(5000);
        if (stop.isAlive()) throw new AssertionError("Service Stop did not return");
        if (!terminalDelivered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Terminal result was not delivered");
        if (ManagementFactory.getThreadMXBean().findDeadlockedThreads() != null) {
            throw new AssertionError("Unexpected JVM deadlock");
        }
        service.close();
        System.out.println("PASS: production ClientScriptService Stop returned, terminal result delivered, no JVM lock cycle");
    }

    private static void awaitRelease(CountDownLatch release) {
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Probe release timed out");
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
