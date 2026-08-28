package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerSessionControllerTest {
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(2);

    @Test
    void appliesExceptionPreferencesOnAttachAndWhileRunning() throws Exception {
        RecordingEngine recording = new RecordingEngine();
        DebuggerSessionController controller = new DebuggerSessionController(
                recording::proxy,
                (target, timeout) -> DebugEngine.Target.local(50_321, timeout)
        );
        try {
            controller.setExceptionBreakpoints(true, false)
                    .get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            controller.acceptTarget(new DebugTargetDescriptor("minecraft", "Minecraft Client", 42));
            awaitPhase(controller, DebuggerSessionController.Phase.DETACHED);
            controller.attach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            assertTrue(recording.caughtExceptions);
            assertFalse(recording.uncaughtExceptions);
            assertEquals(1, recording.exceptionBreakpointUpdates);

            controller.setExceptionBreakpoints(false, true)
                    .get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            assertFalse(recording.caughtExceptions);
            assertTrue(recording.uncaughtExceptions);
            assertEquals(2, recording.exceptionBreakpointUpdates);
        } finally {
            controller.close();
        }
    }

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
            assertEquals(
                    DebuggerSessionController.BreakpointState.UNBOUND,
                    controller.breakpoint(source.uri(), 12).state()
            );

            controller.attach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(DebuggerSessionController.Phase.RUNNING, controller.status().phase());
            assertEquals(1, resolutions.get());
            assertEquals(1, engines.size());
            RecordingEngine first = engines.getFirst();
            assertEquals(List.of(source), first.sources);
            assertEquals(List.of(12), breakpointLines(first, source.uri()));
            assertTrue(first.started);
            assertEquals(
                    DebuggerSessionController.BreakpointState.BOUND,
                    controller.breakpoint(source.uri(), 12).state()
            );

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
            DebugEngine.Variable local = new DebugEngine.Variable(
                    "block", "block", "StoneBlock", "Block", DebugEngine.VariableKind.LOCAL, 0, 0, 0
            );
            engine.frames = List.of(frame);
            engine.scopes = List.of(new DebugEngine.Scope("Local", 9, false));
            engine.variables.put(9, List.of(local));

            engine.fireStopped(new DebugEngine.StoppedEvent("breakpoint", 73, true));
            DebuggerSessionController.Status paused = awaitPhase(controller, DebuggerSessionController.Phase.PAUSED);
            assertEquals("Paused at Block.getId:28", paused.detail());
            assertEquals(List.of(frame), controller.pausedState().frames());
            assertEquals(List.of(local), controller.pausedState().variables());

            DebugEngine.Variable child = new DebugEngine.Variable(
                    "name", "block.name", "stone", "String", DebugEngine.VariableKind.FIELD, 0, 0, 0
            );
            engine.variables.put(15, List.of(child));
            assertEquals(
                    List.of(child),
                    controller.variables(15).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            );
            engine.preview = new DebugEngine.ValuePreview("minecraft:stone", "minecraft:stone");
            assertEquals(
                    engine.preview,
                    controller.preview(15).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
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
                    controller.breakpoint(source.uri(), 19).request()
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

    @Test
    void disabledBreakpointKeepsItsConfigurationAndStaysDisabledAcrossReconnects() throws Exception {
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
                    URI.create("decompiled:///sample/Target.java"),
                    "sample.Target",
                    "class Target {}"
            );
            controller.configureBreakpoint(source, 19, "state != null", "3")
                    .get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            controller.acceptTarget(new DebugTargetDescriptor("minecraft", "Minecraft Client", 42));
            awaitPhase(controller, DebuggerSessionController.Phase.DETACHED);
            controller.attach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            assertFalse(controller.toggleBreakpointEnabled(source, 19)
                    .get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(
                    DebuggerSessionController.BreakpointState.DISABLED,
                    controller.breakpoint(source.uri(), 19).state()
            );
            assertTrue(engines.getFirst().breakpoints.get(source.uri()).isEmpty());

            controller.configureBreakpoint(source, 19, "state.isAir()", "5")
                    .get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            DebuggerSessionController.Breakpoint disabled = controller.breakpoint(source.uri(), 19);
            assertEquals(DebuggerSessionController.BreakpointState.DISABLED, disabled.state());
            assertEquals(
                    new DebugEngine.SourceBreakpoint(19, "state.isAir()", "5", null),
                    disabled.request()
            );
            assertTrue(engines.getFirst().breakpoints.get(source.uri()).isEmpty());

            controller.detach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            controller.attach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(DebuggerSessionController.BreakpointState.DISABLED,
                    controller.breakpoint(source.uri(), 19).state());
            assertTrue(engines.get(1).breakpoints.get(source.uri()).isEmpty());

            engines.get(1).fireBreakpointChanged(new DebugEngine.Breakpoint(19, 19, true, ""));
            assertEquals(DebuggerSessionController.BreakpointState.DISABLED,
                    controller.breakpoint(source.uri(), 19).state());

            assertTrue(controller.toggleBreakpointEnabled(source, 19)
                    .get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            DebuggerSessionController.Breakpoint enabled = controller.breakpoint(source.uri(), 19);
            assertEquals(DebuggerSessionController.BreakpointState.BOUND, enabled.state());
            assertEquals(disabled.request(), enabled.request());
            assertEquals(List.of(disabled.request()), engines.get(1).breakpoints.get(source.uri()));
        } finally {
            controller.close();
        }
    }

    @Test
    void methodBreakpointKeepsItsDeclarationLineAndUsesItsExactEntryLine() throws Exception {
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
            DebugEngine.Source source = mappedSource(URI.create("decompiled:///sample/Target.java"), 12);
            DebugEngine.SourceBreakpoint methodBreakpoint = DebugEngine.SourceBreakpoint.methodEntry(
                    10,
                    12,
                    new DebugEngine.MethodTarget("sample.Target", "run", "(I)V"),
                    "value > 0",
                    null
            );
            controller.toggleBreakpoint(source, methodBreakpoint)
                    .get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            DebuggerSessionController.Breakpoint managed = controller.breakpoint(source.uri(), 10);
            assertEquals(10, managed.line());
            assertEquals(12, managed.request().debuggerLine());
            assertEquals(methodBreakpoint.method(), managed.request().method());

            controller.acceptTarget(new DebugTargetDescriptor("minecraft", "Minecraft Client", 42));
            awaitPhase(controller, DebuggerSessionController.Phase.DETACHED);
            controller.attach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            assertEquals(List.of(12), breakpointLines(engines.getFirst(), source.uri()));
            assertEquals("value > 0", engines.getFirst().breakpoints.get(source.uri()).getFirst().condition());
        } finally {
            controller.close();
        }
    }

    @Test
    void methodWithoutExecutableBytecodeIsInvalidAndNeverSent() throws Exception {
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
            DebugEngine.Source source = mappedSource(URI.create("decompiled:///sample/Target.java"), 12);
            DebugEngine.SourceBreakpoint methodBreakpoint = DebugEngine.SourceBreakpoint.methodEntry(
                    10,
                    0,
                    new DebugEngine.MethodTarget("sample.Target", "abstractRun", "()V"),
                    null,
                    null
            );
            controller.toggleBreakpoint(source, methodBreakpoint)
                    .get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            DebuggerSessionController.Breakpoint managed = controller.breakpoint(source.uri(), 10);
            assertEquals(DebuggerSessionController.BreakpointState.INVALID, managed.state());
            assertEquals("Method has no executable bytecode", managed.detail());

            controller.acceptTarget(new DebugTargetDescriptor("minecraft", "Minecraft Client", 42));
            awaitPhase(controller, DebuggerSessionController.Phase.DETACHED);
            controller.attach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(engines.getFirst().breakpoints.getOrDefault(source.uri(), List.of()).isEmpty());
        } finally {
            controller.close();
        }
    }

    @Test
    void bindingStateFollowsDebuggerVerificationAndDetach() throws Exception {
        List<RecordingEngine> engines = new ArrayList<>();
        DebuggerSessionController controller = new DebuggerSessionController(
                () -> {
                    RecordingEngine engine = new RecordingEngine();
                    engine.breakpointVerified = false;
                    engines.add(engine);
                    return engine.proxy();
                },
                (target, timeout) -> DebugEngine.Target.local(50_321, timeout)
        );
        try {
            DebugEngine.Source source = mappedSource(URI.create("decompiled:///sample/Target.java"), 7);
            controller.acceptTarget(new DebugTargetDescriptor("minecraft", "Minecraft Client", 42));
            awaitPhase(controller, DebuggerSessionController.Phase.DETACHED);
            controller.toggleBreakpoint(source, 7).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(
                    DebuggerSessionController.BreakpointState.UNBOUND,
                    controller.breakpoint(source.uri(), 7).state()
            );

            controller.attach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(
                    DebuggerSessionController.BreakpointState.PENDING,
                    controller.breakpoint(source.uri(), 7).state()
            );

            engines.getFirst().fireBreakpointChanged(new DebugEngine.Breakpoint(7, 7, true, ""));
            awaitBreakpointState(controller, source.uri(), 7, DebuggerSessionController.BreakpointState.BOUND);

            controller.detach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(
                    DebuggerSessionController.BreakpointState.UNBOUND,
                    controller.breakpoint(source.uri(), 7).state()
            );
        } finally {
            controller.close();
        }
    }

    @Test
    void nonExecutableDisplayedLineIsInvalidAndNeverSentToDebugger() throws Exception {
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
            DebugEngine.Source source = mappedSource(URI.create("decompiled:///sample/Target.java"), 7);
            controller.acceptTarget(new DebugTargetDescriptor("minecraft", "Minecraft Client", 42));
            awaitPhase(controller, DebuggerSessionController.Phase.DETACHED);
            controller.attach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            controller.toggleBreakpoint(source, 8).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            DebuggerSessionController.Breakpoint breakpoint = controller.breakpoint(source.uri(), 8);
            assertEquals(DebuggerSessionController.BreakpointState.INVALID, breakpoint.state());
            assertEquals("No executable bytecode is mapped to line 8", breakpoint.detail());
            assertTrue(engines.getFirst().breakpoints.getOrDefault(source.uri(), List.of()).isEmpty());
        } finally {
            controller.close();
        }
    }

    @Test
    void emptyLineMapRemainsRuntimeVerifiableAndLaterMappingCanRejectTheLine() throws Exception {
        DebuggerSessionController controller = new DebuggerSessionController(
                RecordingEngine::newProxy,
                (target, timeout) -> DebugEngine.Target.local(50_321, timeout)
        );
        try {
            URI sourceUri = URI.create("file:///sample/Target.java");
            DebugEngine.Source originalSource = new DebugEngine.Source(sourceUri, "sample.Target", "class Target {}");
            controller.toggleBreakpoint(originalSource, 8).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(
                    DebuggerSessionController.BreakpointState.UNBOUND,
                    controller.breakpoint(sourceUri, 8).state()
            );

            controller.registerSource(mappedSource(sourceUri, 7));
            assertEquals(
                    DebuggerSessionController.BreakpointState.INVALID,
                    controller.breakpoint(sourceUri, 8).state()
            );
        } finally {
            controller.close();
        }
    }

    @Test
    void verificationEventForRemovedBreakpointIsIgnored() throws Exception {
        List<RecordingEngine> engines = new ArrayList<>();
        DebuggerSessionController controller = new DebuggerSessionController(
                () -> {
                    RecordingEngine engine = new RecordingEngine();
                    engine.breakpointVerified = false;
                    engines.add(engine);
                    return engine.proxy();
                },
                (target, timeout) -> DebugEngine.Target.local(50_321, timeout)
        );
        try {
            DebugEngine.Source source = mappedSource(URI.create("decompiled:///sample/Target.java"), 7);
            controller.acceptTarget(new DebugTargetDescriptor("minecraft", "Minecraft Client", 42));
            awaitPhase(controller, DebuggerSessionController.Phase.DETACHED);
            controller.attach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            controller.toggleBreakpoint(source, 7).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            controller.toggleBreakpoint(source, 7).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            engines.getFirst().fireBreakpointChanged(new DebugEngine.Breakpoint(7, 7, true, ""));
            controller.detach().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            assertNull(controller.breakpoint(source.uri(), 7));
        } finally {
            controller.close();
        }
    }

    private static List<Integer> breakpointLines(RecordingEngine engine, URI sourceUri) {
        return engine.breakpoints.getOrDefault(sourceUri, List.of()).stream()
                .map(DebugEngine.SourceBreakpoint::debuggerLine)
                .toList();
    }

    private static DebugEngine.Source mappedSource(URI sourceUri, int... displayedLines) {
        int[] pairs = new int[displayedLines.length * 2];
        for (int index = 0; index < displayedLines.length; index++) {
            pairs[index * 2] = displayedLines[index];
            pairs[index * 2 + 1] = displayedLines[index];
        }
        return new DebugEngine.Source(
                sourceUri,
                "sample.Target",
                "class Target {}",
                SourceLineMap.fromOriginalToDisplayed(pairs)
        );
    }

    private static void awaitBreakpointState(
            DebuggerSessionController controller,
            URI sourceUri,
            int line,
            DebuggerSessionController.BreakpointState state
    ) throws Exception {
        CompletableFuture<Void> reached = new CompletableFuture<>();
        DebuggerSessionController.Listener listener = new DebuggerSessionController.Listener() {
            @Override
            public void breakpointsChanged(
                    URI changedSource,
                    List<DebuggerSessionController.Breakpoint> breakpoints
            ) {
                if (sourceUri.equals(changedSource)
                        && breakpoints.stream().anyMatch(breakpoint -> breakpoint.line() == line
                        && breakpoint.state() == state)) {
                    reached.complete(null);
                }
            }
        };
        controller.addListener(listener);
        try {
            DebuggerSessionController.Breakpoint current = controller.breakpoint(sourceUri, line);
            if (current != null && current.state() == state) {
                return;
            }
            reached.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            controller.removeListener(listener);
        }
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
        private DebugEngine.ValuePreview preview = DebugEngine.ValuePreview.NONE;
        private boolean breakpointVerified = true;
        private boolean caughtExceptions;
        private boolean uncaughtExceptions;
        private int exceptionBreakpointUpdates;

        static DebugEngine newProxy() {
            return new RecordingEngine().proxy();
        }

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
                                            this.breakpointVerified,
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
                        case "preview" -> completed(this.preview);
                        case "threads" -> completed(List.of());
                        case "evaluate" -> completed(this.evaluationResult);
                        case "exceptionInfo" -> completed(new DebugEngine.ExceptionInfo("", "", ""));
                        case "setExceptionBreakpoints" -> {
                            this.caughtExceptions = (Boolean) arguments[0];
                            this.uncaughtExceptions = (Boolean) arguments[1];
                            this.exceptionBreakpointUpdates++;
                            yield completed(null);
                        }
                        case "pause", "stepOver", "stepInto", "stepOut" -> completed(null);
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

        void fireBreakpointChanged(DebugEngine.Breakpoint breakpoint) {
            for (DebugEngine.Listener listener : List.copyOf(this.listeners)) {
                listener.breakpointChanged(breakpoint);
            }
        }

        private static <T> CompletableFuture<T> completed(T value) {
            return CompletableFuture.completedFuture(value);
        }
    }
}
