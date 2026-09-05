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
public class ScriptLifecycleAuditProbe {
    public static void main(String[] args) throws Exception {
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
                if (!releaseSink.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("audit latch timed out");
                accept.invoke(service, id, result, clientSide);
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
        var bean = ManagementFactory.getThreadMXBean();
        long[] deadlocks = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (deadlocks == null && System.nanoTime() < deadline) {
            deadlocks = bean.findDeadlockedThreads();
            Thread.sleep(10);
        }
        if (deadlocks == null) throw new AssertionError("Expected lock cycle did not reproduce");
        System.out.println("CONFIRMED ClientScriptService stop versus compiler result lock inversion");
        for (var info : bean.getThreadInfo(deadlocks, true, true)) System.out.println(info);
        // Only deliberately deadlocked daemon fixture threads remain; process exit releases them.
    }
}
