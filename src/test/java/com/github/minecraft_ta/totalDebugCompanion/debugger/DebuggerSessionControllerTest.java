package com.github.minecraft_ta.totalDebugCompanion.debugger;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerSessionControllerTest {
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(2);

    @Test
    void targetPublicationIsDetachedAndManualReattachUsesAFreshEngine() throws Exception {
        List<RecordingEngine> engines = new ArrayList<>();
        AtomicInteger resolutions = new AtomicInteger();
        DebuggerSessionController controller = new DebuggerSessionController(
                () -> {
                    RecordingEngine engine = new RecordingEngine();
                    engines.add(engine);
                    return engine.proxy();
                },
                (target, timeout) -> {
                    resolutions.incrementAndGet();
                    assertEquals(42, target.processId());
                    return DebugEngine.Target.local(50_321, timeout);
                }
        );
        try {
            DebugTargetDescriptor target = new DebugTargetDescriptor("minecraft", "Minecraft Client", 42);
            controller.acceptTarget(target);
            assertEquals(DebuggerSessionController.Phase.DETACHED, awaitPhase(controller, DebuggerSessionController.Phase.DETACHED).phase());
            assertTrue(engines.isEmpty());
            assertEquals(0, resolutions.get());

            DebugEngine.Source source = new DebugEngine.Source(
                    URI.create("decompiled:///net/minecraft/world/level/block/Block.java"),
                    "net.minecraft.world.level.block.Block",
                    "class Block {}"
            );
            assertTrue(controller.toggleBreakpoint(source, 12).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

            controller.attach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(DebuggerSessionController.Phase.RUNNING, controller.status().phase());
            assertEquals(1, resolutions.get());
            assertEquals(1, engines.size());
            RecordingEngine first = engines.getFirst();
            assertEquals(List.of(source), first.sources);
            assertEquals(List.of(12), breakpointLines(first, source.uri()));
            assertTrue(first.started);

            controller.detach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(DebuggerSessionController.Phase.DETACHED, controller.status().phase());
            assertTrue(first.disconnected);
            assertTrue(first.closed);

            controller.attach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(2, resolutions.get());
            assertEquals(2, engines.size());
            RecordingEngine second = engines.get(1);
            assertNotSame(first, second);
            assertEquals(List.of(12), breakpointLines(second, source.uri()));
        } finally {
            controller.close();
        }
    }

    @Test
    void stopEventLoadsFramesAndLocalsBeforeResume() throws Exception {
        List<RecordingEngine> engines = new ArrayList<>();
        DebuggerSessionController controller = new DebuggerSessionController(
                () -> {
                    RecordingEngine engine = new RecordingEngine();
                    engines.add(engine);
                    return engine.proxy();
                },
                (target, timeout) -> DebugEngine.Target.local(50_321, timeout)
        );
        try {
            controller.acceptTarget(new DebugTargetDescriptor("minecraft", "Minecraft Client", 42));
            awaitPhase(controller, DebuggerSessionController.Phase.DETACHED);
            controller.attach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            RecordingEngine engine = engines.getFirst();
            URI sourceUri = URI.create("decompiled:///net/minecraft/world/level/block/Block.java");
            DebugEngine.StackFrame frame = new DebugEngine.StackFrame(
                    7,
                    "Block.getId",
                    "net.minecraft.world.level.block.Block",
                    sourceUri,
                    28,
                    1
            );
            DebugEngine.Variable local = new DebugEngine.Variable("block", "StoneBlock", "Block", 0, 0, 0);
            engine.frames = List.of(frame);
            engine.scopes = List.of(new DebugEngine.Scope("Local", 9, false));
            engine.variables.put(9, List.of(local));

            engine.fireStopped(new DebugEngine.StoppedEvent("breakpoint", 73, true));
            DebuggerSessionController.Status paused = awaitPhase(controller, DebuggerSessionController.Phase.PAUSED);
            assertEquals("Paused at Block.getId:28", paused.detail());
            assertEquals(List.of(frame), controller.pausedState().frames());
            assertEquals(List.of(local), controller.pausedState().variables());

            DebugEngine.Variable child = new DebugEngine.Variable("name", "stone", "String", 0, 0, 0);
            engine.variables.put(15, List.of(child));
            assertEquals(
                    List.of(child),
                    controller.variables(15).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            );
            engine.evaluationResult = new DebugEngine.EvaluationResult("true", "boolean", 0, 0);
            assertEquals(
                    engine.evaluationResult,
                    controller.evaluate("block != null", frame)
                            .get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            );

            controller.resume().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(DebuggerSessionController.Phase.RUNNING, controller.status().phase());
            assertEquals(73, engine.resumedThread);
        } finally {
            controller.close();
        }
    }

    @Test
    void configuredBreakpointPreservesConditionAndHitCountWhenAttached() throws Exception {
        List<RecordingEngine> engines = new ArrayList<>();
        DebuggerSessionController controller = new DebuggerSessionController(
                () -> {
                    RecordingEngine engine = new RecordingEngine();
                    engines.add(engine);
                    return engine.proxy();
                },
                (target, timeout) -> DebugEngine.Target.local(50_321, timeout)
        );
        try {
            DebugEngine.Source source = new DebugEngine.Source(
                    URI.create("decompiled:///net/minecraft/world/level/block/Block.java"),
                    "net.minecraft.world.level.block.Block",
                    "class Block {}"
            );
            controller.configureBreakpoint(source, 19, "state != null", "3")
                    .get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(
                    new DebugEngine.SourceBreakpoint(19, "state != null", "3", null),
                    controller.breakpoint(source.uri(), 19)
            );

            controller.acceptTarget(new DebugTargetDescriptor("minecraft", "Minecraft Client", 42));
            awaitPhase(controller, DebuggerSessionController.Phase.DETACHED);
            controller.attach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(
                    List.of(new DebugEngine.SourceBreakpoint(19, "state != null", "3", null)),
                    engines.getFirst().breakpoints.get(source.uri())
            );
        } finally {
            controller.close();
        }
    }

    private static List<Integer> breakpointLines(RecordingEngine engine, URI sourceUri) {
        return engine.breakpoints.getOrDefault(sourceUri, List.of()).stream()
                .map(DebugEngine.SourceBreakpoint::line)
                .toList();
    }

    private static DebuggerSessionController.Status awaitPhase(
            DebuggerSessionController controller,
            DebuggerSessionController.Phase phase
    ) throws Exception {
        CompletableFuture<DebuggerSessionController.Status> reached = new CompletableFuture<>();
        DebuggerSessionController.Listener listener = new DebuggerSessionController.Listener() {
            @Override
            public void statusChanged(DebuggerSessionController.Status status) {
                if (status.phase() == phase) {
                    reached.complete(status);
                }
            }
        };
        controller.addListener(listener);
        try {
            return reached.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            controller.removeListener(listener);
        }
    }

    private static final class RecordingEngine {
        private final List<DebugEngine.Source> sources = new ArrayList<>();
        private final List<DebugEngine.Listener> listeners = new ArrayList<>();
        private final Map<URI, List<DebugEngine.SourceBreakpoint>> breakpoints = new HashMap<>();
        private final Map<Integer, List<DebugEngine.Variable>> variables = new HashMap<>();
        private DebugEngine.State state = DebugEngine.State.NEW;
        private List<DebugEngine.StackFrame> frames = List.of();
        private List<DebugEngine.Scope> scopes = List.of();
        private boolean started;
        private boolean disconnected;
        private boolean closed;
        private long resumedThread = -1;
        private DebugEngine.EvaluationResult evaluationResult =
                new DebugEngine.EvaluationResult("", "", 0, 0);

        DebugEngine proxy() {
            return (DebugEngine) Proxy.newProxyInstance(
                    DebugEngine.class.getClassLoader(),
                    new Class<?>[]{DebugEngine.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "registerSource" -> {
                            this.sources.add((DebugEngine.Source) arguments[0]);
                            yield null;
                        }
                        case "addListener" -> {
                            this.listeners.add((DebugEngine.Listener) arguments[0]);
                            yield null;
                        }
                        case "removeListener" -> {
                            this.listeners.remove(arguments[0]);
                            yield null;
                        }
                        case "state" -> this.state;
                        case "attach" -> {
                            this.state = DebugEngine.State.ATTACHED;
                            yield completed(null);
                        }
                        case "setBreakpoints" -> {
                            URI sourceUri = (URI) arguments[0];
                            @SuppressWarnings("unchecked")
                            List<DebugEngine.SourceBreakpoint> requested =
                                    (List<DebugEngine.SourceBreakpoint>) arguments[1];
                            this.breakpoints.put(sourceUri, List.copyOf(requested));
                            yield completed(requested.stream()
                                    .map(breakpoint -> new DebugEngine.Breakpoint(
                                            breakpoint.line(),
                                            breakpoint.line(),
                                            true,
                                            ""
                                    ))
                                    .toList());
                        }
                        case "start" -> {
                            this.started = true;
                            this.state = DebugEngine.State.RUNNING;
                            yield completed(null);
                        }
                        case "stackTrace" -> completed(this.frames);
                        case "scopes" -> completed(this.scopes);
                        case "variables" -> completed(this.variables.getOrDefault((Integer) arguments[0], List.of()));
                        case "threads" -> completed(List.of());
                        case "evaluate" -> completed(this.evaluationResult);
                        case "exceptionInfo" -> completed(new DebugEngine.ExceptionInfo("", "", ""));
                        case "setExceptionBreakpoints", "pause", "stepOver", "stepInto", "stepOut" -> completed(null);
                        case "resume" -> {
                            this.resumedThread = (Long) arguments[0];
                            yield completed(null);
                        }
                        case "disconnect" -> {
                            this.disconnected = true;
                            this.state = DebugEngine.State.TERMINATED;
                            yield completed(null);
                        }
                        case "close" -> {
                            this.closed = true;
                            this.state = DebugEngine.State.CLOSED;
                            yield null;
                        }
                        case "toString" -> "RecordingDebugEngine";
                        default -> throw new AssertionError("Unexpected DebugEngine call " + method.getName());
                    }
            );
        }

        void fireStopped(DebugEngine.StoppedEvent event) {
            this.state = DebugEngine.State.STOPPED;
            for (DebugEngine.Listener listener : List.copyOf(this.listeners)) {
                listener.stopped(event);
            }
        }

        private static <T> CompletableFuture<T> completed(T value) {
            return CompletableFuture.completedFuture(value);
        }
    }
}
