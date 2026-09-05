package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.EvaluationSemanticsDebuggee;
import com.github.minecraft_ta.totalDebugCompanion.debugger.harness.DebuggerTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class EvaluationSemanticsIntegrationTest {
    @Test
    void evaluatesReceiverBeforeArguments() throws Exception {
        withFrame((engine, frame) -> assertEquals("12", value(engine, frame,
                "receiver.receiver().combine(receiver.argument())")));
    }

    @Test
    void reacquiresLocalsAfterInvocation() throws Exception {
        withFrame((engine, frame) -> assertEquals("8", value(engine, frame, "receiver.touch() + local")));
    }

    @Test
    void rejectsUnsupportedSyntaxBeforeExecutingAnyPart() throws Exception {
        withFrame((engine, frame) -> {
            assertThrows(Exception.class, () -> value(engine, frame,
                    "receiver.consume(receiver.touch(), new MissingType())"));
            assertEquals("0", value(engine, frame, "calls"));
        });
    }

    @Test
    void compilesLoopsAndWritesLocalsBack() throws Exception {
        withFrame((engine, frame) -> {
            assertEquals("10", value(engine, frame, "for (int i = 0; i < 3; i++) local++; return local;"));
            assertEquals("10", value(engine, frame, "local"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ int calls = 2; } return calls;",
            "for (int calls = 0; calls < 2; calls++) { } return calls;",
            "for (int calls : new int[] { calls }) { } return calls;",
            "try { throw new Exception(); } catch (Exception calls) { } return calls;",
            "java.util.function.IntUnaryOperator f = calls -> calls + 1; return calls;",
            "java.util.function.IntUnaryOperator f = (int calls) -> calls + 1; return calls;",
            "try (java.io.StringReader calls = new java.io.StringReader(\"x\")) { } return calls;",
            "int before = calls; int calls = 4; return before + calls - 4;",
            "java.util.function.IntUnaryOperator f = calls -> calls + 1; return f.applyAsInt(3) - 4 + calls;",
            "try (java.io.StringReader calls = null) { throw new Exception(); } catch (Exception ignored) { return calls; }"
    })
    void compiledDeclarationsOnlyHideFrameFieldsWithinTheirScope(String source) throws Exception {
        withFrame((engine, frame) -> assertEquals("0", value(engine, frame, source)));
    }

    @Test
    void scopedLocalWritesSurviveTargetExceptions() throws Exception {
        withFrame((engine, frame) -> {
            assertThrows(Exception.class, () -> value(engine, frame,
                    "{ int calls = 3; local = calls; } local += calls; throw new IllegalStateException(\"scope fixture\");"));
            assertEquals("3", value(engine, frame, "local"));
        });
    }

    @Test
    void rejectsFlowScopedPatternsBeforeTargetSideEffects() throws Exception {
        withFrame((engine, frame) -> {
            Exception failure = assertThrows(Exception.class, () -> value(engine, frame,
                    "receiver.touch(); if ((Object) receiver instanceof Object calls) { return calls; } return null;"));
            assertTrue(failure.getCause().getMessage().contains("does not support"));
            assertEquals("0", value(engine, frame, "calls"));
        });
    }

    @Test
    void writesLocalsBackAfterAnException() throws Exception {
        withFrame((engine, frame) -> {
            assertThrows(Exception.class, () -> value(engine, frame,
                    "local = 12; throw new IllegalStateException(\"fixture\");"));
            assertEquals("12", value(engine, frame, "local"));
        });
    }

    @Test
    void compilesLambdaCaptureAndConstruction() throws Exception {
        withFrame((engine, frame) -> assertEquals("9", value(engine, frame,
                "java.util.function.IntSupplier supplier = () -> local + 2; return supplier.getAsInt();")));
    }

    @Test
    void preservesJavaNumericTypesAndOperators() throws Exception {
        withFrame((engine, frame) -> {
            assertEquals("int", engine.evaluate("1 + 2", frame.id()).get().type());
            assertEquals("float", engine.evaluate("1.0f + 2.0f", frame.id()).get().type());
            assertEquals("true", value(engine, frame, "-0.0 == 0.0"));
            assertEquals("false", value(engine, frame, "(0.0 / 0.0) == (0.0 / 0.0)"));
            assertEquals("false", value(engine, frame, "(0.0 / 0.0) > 1.0"));
            assertEquals("2147483647", value(engine, frame, "-1 >>> 1"));
            assertEquals("false", value(engine, frame, "true & false"));
            assertEquals("-2147483648", value(engine, frame, "-2147483648"));
            assertEquals("-9223372036854775808", value(engine, frame, "-9223372036854775808L"));
            assertEquals("java.lang.Class", engine.evaluate("String.class", frame.id()).get().type());
        });
    }

    @Test
    void preservesCompiledNullVoidAndBoxedResults() throws Exception {
        withFrame((engine, frame) -> {
            assertEquals("<void>", engine.evaluate("local++;", frame.id()).get().type());
            assertEquals("null", value(engine, frame, "return null;"));
            var boxed = engine.evaluate("return Integer.valueOf(local);", frame.id()).get();
            assertEquals("java.lang.Integer", boxed.type());
            assertTrue(boxed.variablesReference() > 0);
            assertEquals("2", value(engine, frame, "import java.util.List; return List.of(1, 2).size();"));
        });
    }

    @Test
    void breakpointActionWritesLocalsAndStaysPausedByDefault() throws Exception {
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(EvaluationSemanticsDebuggee.class)) {
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(harness.lineContaining("EVALUATION_STOP"))
                    .withAction(new DebugEngine.BreakpointAction("local += receiver.touch(); return local;", null, false)));
            harness.start();
            var stop = harness.awaitStop("breakpoint action");
            assertEquals("8", value(harness.engine(), harness.firstFrame(stop.threadId()), "local"));
            assertEquals("8", harness.engine().breakpointActionResult().result().value());
        }
    }

    @Test
    void breakpointActionContinuesOnlyAfterSuccessfulScalarResult() throws Exception {
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(EvaluationSemanticsDebuggee.class)) {
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(harness.lineContaining("EVALUATION_STOP"))
                    .withAction(new DebugEngine.BreakpointAction("local = 15; return local;", null, true)));
            harness.start();
            assertEquals("15", harness.readOutputLine("continued breakpoint action"));
            assertEquals(0, harness.awaitExit());
        }
    }

    @Test
    void breakpointActionFailurePreservesPauseAndLocalWrites() throws Exception {
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(EvaluationSemanticsDebuggee.class)) {
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(harness.lineContaining("EVALUATION_STOP"))
                    .withAction(new DebugEngine.BreakpointAction("local = 16; throw new IllegalStateException(\"action fixture\");", null, true)));
            harness.engine().setExceptionBreakpoints(true, true).get();
            harness.start();
            var stop = harness.awaitStop("failed breakpoint action");
            assertEquals("16", value(harness.engine(), harness.firstFrame(stop.threadId()), "local"));
            assertTrue(harness.engine().breakpointActionResult().error().contains("action fixture"));
        }
    }

    @Test
    void savedBreakpointScriptIsReadAgainForEveryHit() throws Exception {
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(EvaluationSemanticsDebuggee.class)) {
            var source = new java.util.concurrent.atomic.AtomicReference<>("local = 8; return local;");
            ((MicrosoftJavaDebugEngine) harness.engine()).breakpointScriptSource(path -> {
                assertEquals("probe.java", path);
                return source.get();
            });
            var action = new DebugEngine.BreakpointAction(null, "probe.java", false);
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(harness.lineContaining("EVALUATION_STOP")).withAction(action),
                    new DebugEngine.SourceBreakpoint(harness.lineContaining("EVALUATION_SECOND")).withAction(action));
            harness.start();
            var first = harness.awaitStop("first saved-script action");
            assertEquals("8", value(harness.engine(), harness.firstFrame(first.threadId()), "local"));
            source.set("local += 3; return local;");
            harness.engine().resume(first.threadId()).get();
            var second = harness.awaitStop("changed saved-script action");
            assertEquals("11", value(harness.engine(), harness.firstFrame(second.threadId()), "local"));
        }
    }

    @Test
    void slowBreakpointActionNeverAutomaticallyContinues() throws Exception {
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(EvaluationSemanticsDebuggee.class)) {
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(harness.lineContaining("EVALUATION_STOP"))
                    .withAction(new DebugEngine.BreakpointAction("Thread.sleep(5200); local = 19; return local;", null, true)));
            harness.start();
            var stop = harness.awaitStop("slow action");
            assertEquals(DebugEngine.State.STOPPED, harness.engine().state());
            assertEquals("19", value(harness.engine(), harness.firstFrame(stop.threadId()), "local"));
            assertTrue(harness.engine().breakpointActionResult().error().contains("slow"));
        }
    }

    @Test
    void objectActionResultRemainsInspectableWhenContinuationIsRejected() throws Exception {
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(EvaluationSemanticsDebuggee.class)) {
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(harness.lineContaining("EVALUATION_STOP"))
                    .withAction(new DebugEngine.BreakpointAction("return new int[] {local};", null, true)));
            harness.start();
            harness.awaitStop("object action result");
            var action = harness.engine().breakpointActionResult();
            assertTrue(action.error().contains("scalar"));
            assertTrue(action.result().variablesReference() > 0);
            assertEquals("7", harness.engine().variables(action.result().variablesReference(), 0, 10).get().getFirst().value());
            assertTrue(harness.engine().variables(action.result().variablesReference(), 10, 10).get().isEmpty());
        }
    }

    @Test
    void cancellingActionByOperationIdKeepsExecutionTrackedAndPaused() throws Exception {
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(EvaluationSemanticsDebuggee.class)) {
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(harness.lineContaining("EVALUATION_STOP"))
                    .withAction(new DebugEngine.BreakpointAction("Thread.sleep(1200); return local;", null, true)));
            harness.start();
            DebuggerEvaluation<?> operation = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (operation == null && System.nanoTime() < deadline) {
                operation = harness.engine().activeEvaluation();
                if (operation == null) Thread.sleep(10);
            }
            assertNotNull(operation);
            assertSame(operation, harness.engine().evaluationOperation(operation.id()));
            operation.cancel();
            harness.awaitStop("cancelled action");
            assertEquals("cancelled", operation.snapshot().state());
            assertSame(operation, harness.engine().evaluationOperation(operation.id()));
        }
    }

    private static String value(DebugEngine engine, DebugEngine.StackFrame frame, String expression) throws Exception {
        return engine.evaluate(expression, frame.id()).get(10, TimeUnit.SECONDS).value();
    }

    private static void withFrame(Assertion assertion) throws Exception {
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(EvaluationSemanticsDebuggee.class)) {
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(harness.lineContaining("EVALUATION_STOP")));
            harness.start();
            var stop = harness.awaitStop("evaluation fixture");
            assertion.run(harness.engine(), harness.firstFrame(stop.threadId()));
        }
    }

    private interface Assertion {
        void run(DebugEngine engine, DebugEngine.StackFrame frame) throws Exception;
    }
}
