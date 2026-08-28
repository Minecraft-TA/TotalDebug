package com.github.minecraft_ta.totalDebugCompanion.debugger.harness;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.ClassBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.DecompilationResult;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.VineflowerDecompiler;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.ConditionalDebuggeeMain;
import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.DecompiledDebuggeeMain;
import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.DebuggeeMain;
import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.ExceptionDebuggeeMain;
import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.FrameNavigationDebuggeeMain;
import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.FrameNavigationImplementation;
import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.LateAttachDebuggeeMain;
import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.PauseDebuggeeMain;
import com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.RichExpressionDebuggeeMain;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionProposal;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;
import net.minecraft.test.GeneratedNamesDebuggeeMain;
import net.minecraft.test.LocalNameDisambiguationFixture;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Real-JDWP debugger scenarios shared by JUnit and {@code DebuggerDevHarness}. */
public final class DebuggerScenarios {
    private static final long TIMEOUT_SECONDS = 10;

    private DebuggerScenarios() {
    }

    public static List<Scenario> all() {
        return List.of(
                new Scenario(
                        "breakpoint-inspection",
                        "breakpoint, inspection, evaluation and step",
                        DebuggerScenarios::breakpointInspectionAndStep
                ),
                new Scenario("stepping", "step into and out", DebuggerScenarios::stepIntoAndOut),
                new Scenario(
                        "breakpoint-conditions",
                        "conditional and hit-count breakpoints",
                        DebuggerScenarios::conditionalAndHitCountBreakpoints
                ),
                new Scenario(
                        "rich-expressions",
                        "runtime member completion, overloads, and private calls",
                        DebuggerScenarios::richExpressions
                ),
                new Scenario("pause-detach", "pause and detach", DebuggerScenarios::pauseAndDetach),
                new Scenario("exceptions", "uncaught exception", DebuggerScenarios::uncaughtException),
                new Scenario(
                        "decompiled-lines",
                        "Vineflower decompiled-source line mapping",
                        DebuggerScenarios::decompiledSourceLineMapping
                ),
                new Scenario(
                        "decompiled-variable-names",
                        "decompiled parameter and local variable names",
                        DebuggerScenarios::decompiledVariableNames
                ),
                new Scenario(
                        "frame-navigation",
                        "source resolution and line mapping for unopened caller frames",
                        DebuggerScenarios::unopenedCallerFrameNavigation
                ),
                new Scenario(
                        "late-attach",
                        "attach, detach and reattach to a running JVM by published process id",
                        DebuggerScenarios::lateAttach
                )
        );
    }

    public static void breakpointInspectionAndStep() throws Exception {
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(DebuggeeMain.class)) {
            int breakpointLine = harness.lineContaining("DEBUG_BREAKPOINT");
            int afterStepLine = harness.lineContaining("DEBUG_AFTER_STEP");
            List<DebugEngine.Breakpoint> breakpoints = harness.setBreakpoints(
                    new DebugEngine.SourceBreakpoint(breakpointLine)
            );
            equal(1, breakpoints.size(), "breakpoint count");
            check(!breakpoints.getFirst().verified(), "Breakpoint was reported bound before JDI installed it");

            harness.start();
            DebugEngine.Breakpoint verified = harness.awaitBreakpointChange("class preparation");
            check(verified.verified(), "Breakpoint was not verified after JDI installed it");
            DebugEngine.StoppedEvent breakpointStop = harness.awaitStop("deferred breakpoint");
            equal("breakpoint", breakpointStop.reason(), "stop reason");

            DebugEngine.StackFrame breakpointFrame = harness.firstFrame(breakpointStop.threadId());
            equal(harness.sourceUri(), breakpointFrame.sourceUri(), "breakpoint source");
            equal(breakpointLine, breakpointFrame.line(), "breakpoint line");
            Map<String, DebugEngine.Variable> variables = harness.variables(breakpointFrame);
            contains(variable(variables, "message").value(), "minecraft", "message value");
            equal("41", variable(variables, "counter").value(), "counter before step");

            Map<String, DebugEngine.Variable> payload = harness.children(variable(variables, "payload"));
            equal("5", variable(payload, "amount").value(), "payload amount");
            contains(variable(payload, "label").value(), "creeper", "payload label");
            Map<String, DebugEngine.Variable> values = harness.children(variable(variables, "values"));
            equal("3", variable(values, "0").value(), "array index 0");
            equal("4", variable(values, "1").value(), "array index 1");
            equal(
                    "true",
                    harness.engine().evaluate(
                                    "counter == 41 && payload.amount == 5 && values[1] == 4",
                                    breakpointFrame.id()
                            )
                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .value(),
                    "watch expression"
            );

            harness.engine().stepOver(breakpointStop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            DebugEngine.StoppedEvent stepStop = harness.awaitStop("step over");
            equal("step", stepStop.reason(), "step stop reason");
            DebugEngine.StackFrame steppedFrame = harness.firstFrame(stepStop.threadId());
            equal(afterStepLine, steppedFrame.line(), "line after step");
            equal("42", variable(harness.variables(steppedFrame), "counter").value(), "counter after step");

            harness.engine().resume(stepStop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            equal(0, harness.awaitExit(), "debuggee exit code");
            harness.awaitDebuggerTermination();
        }
    }

    public static void stepIntoAndOut() throws Exception {
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(DebuggeeMain.class)) {
            int breakpointLine = harness.lineContaining("DEBUG_BREAKPOINT");
            int incrementLine = harness.lineContaining("return value + 1");
            int afterStepLine = harness.lineContaining("DEBUG_AFTER_STEP");
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(breakpointLine));
            harness.start();

            DebugEngine.StoppedEvent breakpointStop = harness.awaitStop("deferred breakpoint");
            harness.engine().stepInto(breakpointStop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            DebugEngine.StoppedEvent stepIntoStop = harness.awaitStop("step into");
            DebugEngine.StackFrame incrementFrame = harness.firstFrame(stepIntoStop.threadId());
            contains(incrementFrame.name(), "increment", "step-into frame name");
            equal(incrementLine, incrementFrame.line(), "step-into line");
            equal("41", variable(harness.variables(incrementFrame), "value").value(), "method argument");

            harness.engine().stepOut(stepIntoStop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            DebugEngine.StoppedEvent stepOutStop = harness.awaitStop("step out");
            DebugEngine.StackFrame mainFrame = harness.firstFrame(stepOutStop.threadId());
            contains(mainFrame.name(), "main", "step-out frame name");
            equal(breakpointLine, mainFrame.line(), "step-out line");

            harness.engine().stepOver(stepOutStop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            DebugEngine.StoppedEvent callerStep = harness.awaitStop("step over in caller");
            DebugEngine.StackFrame callerFrame = harness.firstFrame(callerStep.threadId());
            equal(afterStepLine, callerFrame.line(), "caller line after step");
            equal("42", variable(harness.variables(callerFrame), "counter").value(), "caller counter");

            harness.engine().resume(callerStep.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            equal(0, harness.awaitExit(), "debuggee exit code");
        }
    }

    public static void conditionalAndHitCountBreakpoints() throws Exception {
        assertLoopBreakpoint("isTarget(iteration)", null, "2");
        assertLoopBreakpoint("this != null && iteration == 2", null, "2");
        assertLoopBreakpoint(null, "2", "1");
    }

    public static void richExpressions() throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of(System.getProperty("user.dir"), "src", "test", "java")
                .resolve(RichExpressionDebuggeeMain.class.getName().replace('.', '/') + ".java")
                .toAbsolutePath().normalize();
        DebugEngine.Source source = new DebugEngine.Source(
                path.toUri(), RichExpressionDebuggeeMain.class.getName(),
                java.nio.file.Files.readString(path),
                com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap.empty(),
                SourceVariableNames.forMethod(
                        "debugExpressions",
                        "()V",
                        Map.of("target", "renamedTarget", "local", "renamedLocal")
                )
        );
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(RichExpressionDebuggeeMain.class, source)) {
            int staticLine = harness.lineContaining("DEBUG_RICH_STATIC_COMPLETION");
            int line = harness.lineContaining("DEBUG_RICH_EXPRESSION");
            int lifecycleLine = harness.lineContaining("DEBUG_RICH_LIFECYCLE");
            harness.setBreakpoints(
                    new DebugEngine.SourceBreakpoint(staticLine),
                    new DebugEngine.SourceBreakpoint(line),
                    new DebugEngine.SourceBreakpoint(lifecycleLine)
            );
            harness.start();
            DebugEngine.StoppedEvent staticStop = harness.awaitStop("rich static completion breakpoint");
            DebugEngine.StackFrame staticFrame = harness.firstFrame(staticStop.threadId());
            List<DebuggerCompletionProposal> staticCompletions = harness.engine()
                    .completions("", 0, staticFrame.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            check(staticCompletions.stream().noneMatch(item -> item.label().equals("this")
                            || item.label().equals("super")),
                    "static-frame completion offered this or super");
            harness.engine().resume(staticStop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            DebugEngine.StoppedEvent stop = harness.awaitStop("rich expression breakpoint");
            DebugEngine.StackFrame frame = harness.firstFrame(stop.threadId());
            Map<String, DebugEngine.Variable> variables = harness.variables(frame);
            DebugEngine.Variable targetVariable = variable(variables, "renamedTarget");
            equal(DebugEngine.VariableKind.THIS, variable(variables, "this").kind(), "this variable kind");
            equal(DebugEngine.VariableKind.LOCAL, targetVariable.kind(), "local variable kind");
            equal(DebugEngine.VariableKind.LOCAL, variable(variables, "warmedBoxingType").kind(), "local variable kind");
            equal(RichExpressionDebuggeeMain.class.getName() + "$Child", targetVariable.type(),
                    "qualified debugger type");
            equal(DebugEngine.VariableKind.FIELD,
                    variable(harness.children(targetVariable), "ownSecret").kind(), "field variable kind");
            check(!harness.engine().preview(targetVariable.variablesReference())
                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).available(),
                    "Unregistered debugger value received an automatic preview");

            equal("\"own-secret\"", value(harness.engine().evaluate("renamedTarget.ownSecret", frame.id())), "private field");
            equal("\"child-hidden\"", value(harness.engine().evaluate("renamedTarget.inheritedSecret", frame.id())), "hidden child field");
            equal("\"inherited-secret\"", value(harness.engine().evaluate("super.inheritedSecret", frame.id())), "super private field");
            equal("\"inherited-secret\"", value(harness.engine().evaluate("renamedTarget.privateBaseCall()", frame.id())), "inherited private method");
            equal("\"child\"", value(harness.engine().evaluate("renamedTarget.virtualCall()", frame.id())), "virtual dispatch");
            equal("\"default-interface\"", value(harness.engine().evaluate("renamedTarget.defaultCall()", frame.id())), "interface default method");
            equal("\"child-overridable\"", value(harness.engine().evaluate("renamedTarget.overridable()", frame.id())), "overridden virtual call");
            equal("\"base-overridable\"", value(harness.engine().evaluate("super.overridable()", frame.id())), "super nonvirtual call");
            equal("\"inherited-secret\"", value(harness.engine().evaluate("declaredTarget.inheritedSecret", frame.id())), "declared receiver field type");
            equal("\"child\"", value(harness.engine().evaluate("declaredTarget.virtualCall()", frame.id())), "declared receiver virtual dispatch");
            equal("\"inherited-secret\"", value(harness.engine().evaluate("((RichExpressionDebuggeeMain.Base) renamedTarget).inheritedSecret", frame.id())), "cast static field type");
            equal("\"static-child\"", value(harness.engine().evaluate("RichExpressionDebuggeeMain.Child.staticChild()", frame.id())), "type-qualified static call");
            try {
                harness.engine().evaluate("RichExpressionDebuggeeMain.Child.overridable()", frame.id())
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                throw new AssertionError("Type-qualified instance method unexpectedly invoked");
            } catch (java.util.concurrent.ExecutionException expected) {
                check(expected.toString().contains("No compatible overload"),
                        "Type-qualified instance method failure was not explicit: " + expected);
            }
            equal("\"int\"", value(harness.engine().evaluate("renamedTarget.overload(1)", frame.id())), "exact overload");
            equal("\"int\"", value(harness.engine().evaluate("renamedTarget.overload((short) 1)", frame.id())), "primitive widening overload");
            equal("\"long\"", value(harness.engine().evaluate("renamedTarget.overload((long) 1)", frame.id())), "primitive widening overload");
            equal("\"string\"", value(harness.engine().evaluate("renamedTarget.overload(\"text\")", frame.id())), "reference overload");
            equal("\"boxed3\"", value(harness.engine().evaluate("renamedTarget.boxed(3)", frame.id())), "boxing");
            equal("\"unboxed0\"", value(harness.engine().evaluate("renamedTarget.unboxed(warmedBoxingType)", frame.id())), "unboxing");
            equal("\"unboxed-long0\"", value(harness.engine().evaluate("renamedTarget.unboxedLong(warmedBoxingType)", frame.id())), "unboxing plus widening");
            equal("\"boolean-true\"", value(harness.engine().evaluate("renamedTarget.booleanAccepted(warmedBooleanType)", frame.id())), "Boolean unboxing");
            equal("\"int\"", value(harness.engine().evaluate("renamedTarget.overload((char) 1)", frame.id())), "char widening");
            equal("\"fixed\"", value(harness.engine().evaluate("renamedTarget.fixed(\"x\")", frame.id())), "fixed arity beats varargs");
            try {
                harness.engine().evaluate("renamedTarget.fixed(null)", frame.id())
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                throw new AssertionError("fixed/string-varargs null overload unexpectedly succeeded");
            } catch (java.util.concurrent.ExecutionException expected) {
                check(expected.toString().contains("Ambiguous overload"),
                        "fixed/string-varargs null overload was not ambiguous: " + expected);
            }
            equal("\"a,b\"", value(harness.engine().evaluate("renamedTarget.varargs(strings)", frame.id())), "varargs direct array");
            equal("\"a,b\"", value(harness.engine().evaluate("renamedTarget.varargs(\"a\", \"b\")", frame.id())), "varargs");
            equal("\"array-null\"", value(harness.engine().evaluate("renamedTarget.varargsOrNull(null)", frame.id())), "varargs null array");
            equal("\"worker-complete\"", value(harness.engine().evaluate("renamedTarget.waitsForWorker()", frame.id())),
                    "multi-thread evaluation");
            equal("\"static-secret\"", value(harness.engine().evaluate("RichExpressionDebuggeeMain.staticCall()", frame.id())), "private static call");
            equal("\"correct\"", value(harness.engine().evaluate("Blocks.CORRECT", frame.id())),
                    "imported simple type name");
            List<DebuggerCompletionProposal> typeCompletions = harness.engine()
                    .completions("Blo", 3, frame.id())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            DebuggerCompletionProposal importedBlocks = typeCompletions.stream()
                    .filter(item -> item.kind() == DebuggerCompletionProposal.Kind.TYPE
                            && item.insertionText().equals("Blocks"))
                    .findFirst().orElseThrow(() -> new AssertionError("imported type missing from completion"));
            check(importedBlocks.detail().endsWith("fixture.z.Blocks"),
                    "imported type completion resolved the wrong Blocks: " + importedBlocks);
            check(typeCompletions.stream().anyMatch(item ->
                            item.kind() == DebuggerCompletionProposal.Kind.TYPE
                                    && item.insertionText().endsWith("fixture.a.Blocks")),
                    "out-of-scope duplicate type missing from completion");
            List<DebuggerCompletionProposal> staticTypeMembers = harness.engine()
                    .completions("Blocks.", "Blocks.".length(), frame.id())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            check(staticTypeMembers.stream().anyMatch(item -> item.label().equals("CORRECT")),
                    "imported type members missing from completion");
            check(staticTypeMembers.stream().noneMatch(item -> item.label().equals("WRONG")),
                    "member completion used the wrong same-named type");
            String highlightedExpression = "Blocks.CORRECT + renamedTarget.sideEffect()";
            List<DebugEngine.ExpressionToken> expressionTokens = harness.engine()
                    .expressionTokens(highlightedExpression, frame.id())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            check(expressionTokens.contains(new DebugEngine.ExpressionToken(
                            0, "Blocks".length(), DebugEngine.ExpressionTokenKind.TYPE)),
                    "runtime semantic tokens did not classify the imported type");
            check(expressionTokens.contains(new DebugEngine.ExpressionToken(
                            "Blocks.".length(), "CORRECT".length(), DebugEngine.ExpressionTokenKind.FIELD)),
                    "runtime semantic tokens did not classify the static field");
            int methodOffset = highlightedExpression.indexOf("sideEffect");
            check(expressionTokens.contains(new DebugEngine.ExpressionToken(
                            methodOffset, "sideEffect".length(), DebugEngine.ExpressionTokenKind.METHOD)),
                    "runtime semantic tokens did not classify the method");
            harness.engine().evaluate("java.util.List.of()", frame.id())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            equal("true", value(harness.engine().evaluate("renamedTarget instanceof java.lang.Object", frame.id())), "instanceof");
            equal("0", value(harness.engine().evaluate("renamedTarget.completionCalls", frame.id())), "completion counter before");

            List<DebuggerCompletionProposal> completions = harness.engine()
                    .completions("renamedTarget.", "renamedTarget.".length(), frame.id())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            check(completions.stream().anyMatch(item -> item.label().equals("ownSecret")), "private field missing from completion");
            check(completions.stream().anyMatch(item -> item.label().startsWith("privateBaseCall")), "inherited method missing from completion");
            check(completions.stream().anyMatch(item -> item.label().startsWith("overload")), "overload missing from completion");
            check(completions.stream().allMatch(item -> item.replacementStart() == "renamedTarget.".length()
                    && item.replacementEnd() == "renamedTarget.".length()), "completion replacement range is not after dot");
            String compoundCompletion = "renamedTarget.ownSecret + renamedTarget.";
            List<DebuggerCompletionProposal> compoundCompletions = harness.engine()
                    .completions(compoundCompletion, compoundCompletion.length(), frame.id())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            check(compoundCompletions.stream().anyMatch(item -> item.label().equals("ownSecret")),
                    "compound-expression member completion did not isolate the owner at the caret");
            check(compoundCompletions.stream().allMatch(item -> item.replacementStart() == compoundCompletion.length()
                            && item.replacementEnd() == compoundCompletion.length()),
                    "compound-expression completion replacement range is not after the final dot");
            String middleCompletion = "renamedTarget.ownSecret";
            int middleCaret = "renamedTarget.o".length();
            List<DebuggerCompletionProposal> middleCompletions = harness.engine()
                    .completions(middleCompletion, middleCaret, frame.id())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            DebuggerCompletionProposal ownSecret = middleCompletions.stream()
                    .filter(item -> item.label().equals("ownSecret"))
                    .findFirst().orElseThrow(() -> new AssertionError("middle-token completion missing"));
            equal("renamedTarget.".length(), ownSecret.replacementStart(), "middle completion start");
            equal(middleCompletion.length(), ownSecret.replacementEnd(), "middle completion end");
            List<DebuggerCompletionProposal> chained = harness.engine()
                    .completions("renamedTarget.sideEffect().", "renamedTarget.sideEffect().".length(), frame.id())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            check(chained.stream().anyMatch(item -> item.label().equals("ownSecret")),
                    "chained completion did not resolve the method return type");
            List<DebuggerCompletionProposal> overloadChained = harness.engine()
                    .completions("renamedTarget.completionOverload(\"text\").",
                            "renamedTarget.completionOverload(\"text\").".length(), frame.id())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            check(overloadChained.stream().anyMatch(item -> item.label().equals("ownSecret")),
                    "chained completion did not select the statically compatible overload");
            equal("0", value(harness.engine().evaluate("renamedTarget.completionCalls", frame.id())),
                    "member completion evaluated its owner");

            try {
                harness.engine().evaluate("renamedTarget.boxedLong(3)", frame.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                throw new AssertionError("int-to-Long conversion unexpectedly succeeded");
            } catch (java.util.concurrent.ExecutionException expected) {
                check(expected.toString().contains("No compatible overload"), "Invalid int-to-Long conversion was accepted");
            }
            equal("\"string-null\"", value(harness.engine().evaluate("renamedTarget.nullOverload(null)", frame.id())),
                    "most-specific null overload");
            try {
                harness.engine().evaluate("renamedTarget.nullUnrelated(null)", frame.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                throw new AssertionError("Unrelated null overload unexpectedly succeeded");
            } catch (java.util.concurrent.ExecutionException expected) {
                check(expected.toString().contains("Ambiguous overload"), "Unrelated null overload ambiguity was not explicit");
            }
            try {
                harness.engine().evaluate("renamedTarget.throwing()", frame.id())
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                throw new AssertionError("Target exception unexpectedly completed evaluation");
            } catch (java.util.concurrent.ExecutionException expected) {
                check(expected.getCause() != null
                                && expected.toString().contains("java.lang.IllegalStateException")
                                && expected.toString().contains("rich-expression-target-failure")
                                && hasCause(expected, com.sun.jdi.InvocationException.class),
                        "Target exception was not preserved: " + expected);
            }
            equal("\"lifecycle-probe\"",
                    value(harness.engine().evaluate("renamedTarget.lifecycleProbe()", frame.id())),
                    "evaluation breakpoint suppression");
            harness.engine().resume(stop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            DebugEngine.StoppedEvent normalLifecycleStop = harness.awaitStop("normal lifecycle breakpoint");
            DebugEngine.StackFrame normalLifecycleFrame = harness.firstFrame(normalLifecycleStop.threadId());
            equal(lifecycleLine, normalLifecycleFrame.line(),
                    "normal invocation breakpoint delivery");
            harness.engine().resume(normalLifecycleStop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            equal(0, harness.awaitExit(), "rich expression debuggee exit code");
        }
    }

    private static String value(java.util.concurrent.CompletableFuture<DebugEngine.EvaluationResult> evaluation)
            throws Exception {
        return evaluation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).value();
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    public static void pauseAndDetach() throws Exception {
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(PauseDebuggeeMain.class)) {
            int pauseLine = harness.lineContaining("DEBUG_PAUSE_LOCATION");
            harness.start();
            equal("ready", harness.readOutputLine("debuggee readiness"), "readiness output");

            DebugEngine.DebugThread mainThread = harness.mainThread();
            harness.engine().pause(mainThread.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            DebugEngine.StoppedEvent pauseStop = harness.awaitStop("pause");
            equal("pause", pauseStop.reason(), "pause reason");
            DebugEngine.StackFrame frame = harness.applicationFrame(pauseStop.threadId());
            equal(pauseLine, frame.line(), "paused line");
            equal("73", variable(harness.variables(frame), "sentinel").value(), "paused local");

            harness.engine().disconnect().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            equal(DebugEngine.State.TERMINATED, harness.engine().state(), "state after detach");
            harness.closeInput();
            equal(0, harness.awaitExit(), "debuggee exit code after detach");
        }
    }

    public static void uncaughtException() throws Exception {
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(ExceptionDebuggeeMain.class)) {
            int exceptionLine = harness.lineContaining("DEBUG_EXCEPTION_LOCATION");
            harness.engine().setExceptionBreakpoints(false, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            harness.start();

            DebugEngine.StoppedEvent exceptionStop = harness.awaitStop("uncaught exception");
            equal("exception", exceptionStop.reason(), "exception stop reason");
            DebugEngine.ExceptionInfo exceptionInfo = harness.engine().exceptionInfo(exceptionStop.threadId())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            contains(exceptionInfo.typeName(), "IllegalStateException", "exception type");
            DebugEngine.StackFrame frame = harness.firstFrame(exceptionStop.threadId());
            equal(harness.sourceUri(), frame.sourceUri(), "exception source");
            equal(exceptionLine, frame.line(), "exception line");

            harness.engine().resume(exceptionStop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            check(harness.awaitExit() != 0, "Exception debuggee unexpectedly exited successfully");
        }
    }

    public static void decompiledSourceLineMapping() throws Exception {
        DecompilationResult decompiled = new VineflowerDecompiler().decompile(
                DecompiledDebuggeeMain.class.getName(),
                classPathSource(DecompiledDebuggeeMain.class.getClassLoader())
        );
        URI sourceUri = URI.create("decompiled:///" + DecompiledDebuggeeMain.class.getName()
                .replace('.', '/') + ".java");
        DebugEngine.Source source = new DebugEngine.Source(
                sourceUri,
                DecompiledDebuggeeMain.class.getName(),
                decompiled.source(),
                decompiled.lineMap()
        );

        int invalidLine = lineContaining(decompiled.source(), "package ");
        check(
                !containsMappedLine(decompiled.lineMap().displayedToOriginal(), invalidLine),
                "Vineflower unexpectedly mapped the package declaration to bytecode"
        );
        check(
                hasTrimmedLineWithMapping(
                        decompiled.source(),
                        "}",
                        decompiled.lineMap().displayedToOriginal(),
                        true
                ),
                "Fixture has no executable closing brace" + System.lineSeparator() + decompiled.source()
        );
        check(
                hasTrimmedLineWithMapping(
                        decompiled.source(),
                        "}",
                        decompiled.lineMap().displayedToOriginal(),
                        false
                ),
                "Fixture has no non-executable closing brace" + System.lineSeparator() + decompiled.source()
        );
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(DecompiledDebuggeeMain.class, source)) {
            List<DebugEngine.Breakpoint> breakpoints = harness.setBreakpoints(
                    new DebugEngine.SourceBreakpoint(invalidLine)
            );
            equal(1, breakpoints.size(), "invalid decompiled breakpoint count");
            check(!breakpoints.getFirst().verified(), "Source-only line was incorrectly reported as verified");
            harness.start();
            equal(0, harness.awaitExit(), "debuggee with an invalid breakpoint exit code");
        }

        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(DecompiledDebuggeeMain.class, source)) {
            int displayedLine = harness.lineContaining("System.out.println(++value);");
            int originalLine = mappedLine(decompiled.lineMap().displayedToOriginal(), displayedLine);
            check(displayedLine != originalLine, "Fixture did not produce different original and decompiled lines");

            List<DebugEngine.Breakpoint> breakpoints = harness.setBreakpoints(
                    new DebugEngine.SourceBreakpoint(displayedLine)
            );
            equal(1, breakpoints.size(), "decompiled breakpoint count");
            check(!breakpoints.getFirst().verified(), "Decompiled breakpoint was bound before class preparation");
            equal(displayedLine, breakpoints.getFirst().line(), "pending decompiled breakpoint line");
            harness.start();

            DebugEngine.Breakpoint verified = harness.awaitBreakpointChange("decompiled class preparation");
            check(verified.verified(), "Decompiled breakpoint was not verified after JDI installation");
            equal(displayedLine, verified.line(), "verified decompiled breakpoint line");

            DebugEngine.StoppedEvent stop = harness.awaitStop("decompiled-source breakpoint");
            DebugEngine.StackFrame frame = harness.firstFrame(stop.threadId());
            equal(sourceUri, frame.sourceUri(), "decompiled frame source");
            equal(displayedLine, frame.line(), "decompiled frame line");
            equal("10", variable(harness.variables(frame), "value").value(), "decompiled frame local");

            harness.engine().resume(stop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            equal(0, harness.awaitExit(), "decompiled debuggee exit code");
        }

        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(DecompiledDebuggeeMain.class, source)) {
            int declarationLine = harness.lineContaining("public static void main(String[]");
            int lastLine = decompiled.source().split("\\R", -1).length;
            int entryLine = decompiled.lineMap()
                    .firstMappedDisplayedLine(declarationLine, lastLine)
                    .orElseThrow(() -> new AssertionError("Decompiled main method has no executable line"));
            check(entryLine != declarationLine, "Fixture method declaration unexpectedly has bytecode");

            DebugEngine.SourceBreakpoint methodBreakpoint = DebugEngine.SourceBreakpoint.methodEntry(
                    declarationLine,
                    entryLine,
                    new DebugEngine.MethodTarget(
                            DecompiledDebuggeeMain.class.getName(),
                            "main",
                            "([Ljava/lang/String;)V"
                    ),
                    null,
                    null
            );
            List<DebugEngine.Breakpoint> breakpoints = harness.setBreakpoints(methodBreakpoint);
            equal(1, breakpoints.size(), "method breakpoint count");
            check(!breakpoints.getFirst().verified(), "Method breakpoint bound before class preparation");
            harness.start();

            DebugEngine.Breakpoint verified = harness.awaitBreakpointChange("method class preparation");
            check(verified.verified(), "Method breakpoint was not verified after JDI installation");
            DebugEngine.StoppedEvent stop = harness.awaitStop("method-entry breakpoint");
            DebugEngine.StackFrame frame = harness.firstFrame(stop.threadId());
            equal(sourceUri, frame.sourceUri(), "method breakpoint source");
            equal(entryLine, frame.line(), "method breakpoint entry line");

            harness.engine().resume(stop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            equal(0, harness.awaitExit(), "method breakpoint debuggee exit code");
        }
    }

    public static void decompiledVariableNames() throws Exception {
        DecompilationResult decompiled = new VineflowerDecompiler().decompile(
                GeneratedNamesDebuggeeMain.class.getName(),
                classPathSource(GeneratedNamesDebuggeeMain.class.getClassLoader())
        );
        URI sourceUri = URI.create("decompiled:///" + GeneratedNamesDebuggeeMain.class.getName()
                .replace('.', '/') + ".java");
        DebugEngine.Source source = new DebugEngine.Source(
                sourceUri,
                GeneratedNamesDebuggeeMain.class.getName(),
                decompiled.source(),
                decompiled.lineMap(),
                decompiled.variableNames()
        );
        int breakpointLine = lineContaining(decompiled.source(), "System.out.println");

        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(GeneratedNamesDebuggeeMain.class, source)) {
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(breakpointLine));
            harness.start();
            DebugEngine.StoppedEvent stopped = harness.awaitStop("decompiled variable names");
            DebugEngine.StackFrame frame = harness.firstFrame(stopped.threadId());
            Map<String, DebugEngine.Variable> variables = harness.variables(frame);

            check(variables.containsKey("s"), "Debugger did not expose Vineflower parameter name 's': "
                    + variables.keySet());
            check(variables.containsKey("i"), "Debugger did not expose Vineflower parameter name 'i': "
                    + variables.keySet());
            check(variables.containsKey("s1") && variables.containsKey("j"),
                    "Debugger did not expose Vineflower local names: " + variables.keySet());
            equal(DebugEngine.VariableKind.PARAMETER, variable(variables, "s").kind(), "decompiled parameter kind");
            equal(DebugEngine.VariableKind.PARAMETER, variable(variables, "i").kind(), "decompiled parameter kind");
            equal(DebugEngine.VariableKind.LOCAL, variable(variables, "s1").kind(), "decompiled local kind");
            check(variables.keySet().stream().noneMatch(name -> name.startsWith("p_") || name.startsWith("var")),
                    "Debugger leaked raw generated variable names: " + variables.keySet());
            equal(
                    "true",
                    harness.engine().evaluate("i == 42 && j == i && s1 == s", frame.id())
                            .thenApply(DebugEngine.EvaluationResult::value)
                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "displayed-name evaluation"
            );

            harness.engine().resume(stopped.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            equal(0, harness.awaitExit(), "generated-name debuggee exit code");
        }

        DecompilationResult slotReuse = new VineflowerDecompiler().decompile(
                LocalNameDisambiguationFixture.class.getName(),
                classPathSource(LocalNameDisambiguationFixture.class.getClassLoader())
        );
        DebugEngine.Source slotReuseSource = new DebugEngine.Source(
                URI.create("decompiled:///" + LocalNameDisambiguationFixture.class.getName()
                        .replace('.', '/') + ".java"),
                LocalNameDisambiguationFixture.class.getName(),
                slotReuse.source(),
                slotReuse.lineMap(),
                slotReuse.variableNames()
        );
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(
                LocalNameDisambiguationFixture.class,
                slotReuseSource
        )) {
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(
                    lineContaining(slotReuse.source(), "if (blockstate1.equals(blockstate))")
            ));
            harness.start();
            DebugEngine.StoppedEvent stopped = harness.awaitStop("slot-reused local name");
            DebugEngine.StackFrame frame = harness.firstFrame(stopped.threadId());
            Map<String, DebugEngine.Variable> variables = harness.variables(frame);

            check(variables.containsKey("blockstate1"), "Debugger did not preserve the readable LVT local name: "
                    + variables.keySet());
            equal(
                    "false",
                    harness.engine().evaluate("blockstate1.equals(blockstate)", frame.id())
                            .thenApply(DebugEngine.EvaluationResult::value)
                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Vineflower-disambiguated local evaluation"
            );

            harness.engine().resume(stopped.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            DebugEngine.StoppedEvent secondStop = harness.awaitStop("second slot-reused local iteration");
            harness.engine().resume(secondStop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            equal(0, harness.awaitExit(), "slot-reused-name debuggee exit code");
        }
    }

    public static void unopenedCallerFrameNavigation() throws Exception {
        DebugEngine.Source targetSource = decompiledSourceFor(FrameNavigationImplementation.class);
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(
                FrameNavigationDebuggeeMain.class,
                targetSource
        )) {
            int breakpointLine = harness.lineContaining("return value + 1");
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(breakpointLine));
            harness.start();

            DebugEngine.StoppedEvent stop = harness.awaitStop("frame-navigation breakpoint");
            List<DebugEngine.StackFrame> frames = harness.engine().stackTrace(stop.threadId())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            DebugEngine.StackFrame implementation = frames.getFirst();
            equal(
                    FrameNavigationImplementation.class.getName(),
                    implementation.binaryName(),
                    "runtime implementation class"
            );
            equal(targetSource.uri(), implementation.sourceUri(), "runtime implementation source");
            equal(breakpointLine, implementation.line(), "runtime implementation line");
            DebugEngine.StackFrame caller = frames.stream()
                    .filter(frame -> frame.name().contains("FrameNavigationDebuggeeMain.main"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Debugger returned no caller frame"));
            DebugEngine.Source callerSource = decompiledSourceFor(FrameNavigationDebuggeeMain.class);
            equal(callerSource.uri(), caller.sourceUri(), "unopened caller source");
            int callerLine = lineContaining(callerSource.contents(), "target.stopHere(41)");
            equal(callerLine, caller.line(), "unopened caller mapped line");

            harness.engine().resume(stop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            equal(0, harness.awaitExit(), "frame-navigation debuggee exit code");
        }
    }

    public static void lateAttach() throws Exception {
        try (DebuggerTestHarness harness = DebuggerTestHarness.launchRunningByProcessId(LateAttachDebuggeeMain.class)) {
            int breakpointLine = harness.lineContaining("DEBUG_LATE_ATTACH");
            equal("ready", harness.readOutputLine("late-attach readiness"), "late-attach readiness output");
            harness.engine().disconnect().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            equal(DebugEngine.State.TERMINATED, harness.engine().state(), "state after first PID detach");
            harness.reattachByProcessId();
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(breakpointLine));
            harness.start();
            harness.closeInput();

            DebugEngine.StoppedEvent stop = harness.awaitStop("late-attach breakpoint");
            DebugEngine.StackFrame frame = harness.firstFrame(stop.threadId());
            equal(breakpointLine, frame.line(), "late-attach breakpoint line");
            equal("87", variable(harness.variables(frame), "sentinel").value(), "late-attach local");

            harness.engine().resume(stop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            equal(0, harness.awaitExit(), "late-attach debuggee exit code");
        }
    }

    private static void assertLoopBreakpoint(
            String condition,
            String hitCondition,
            String expectedIteration
    ) throws Exception {
        try (DebuggerTestHarness harness = DebuggerTestHarness.launch(ConditionalDebuggeeMain.class)) {
            int line = harness.lineContaining("DEBUG_CONDITIONAL_BREAKPOINT");
            harness.setBreakpoints(new DebugEngine.SourceBreakpoint(line, condition, hitCondition, null));
            harness.start();

            DebugEngine.StoppedEvent stop = harness.awaitStop("conditional or hit-count breakpoint");
            DebugEngine.StackFrame frame = harness.firstFrame(stop.threadId());
            equal(expectedIteration, variable(harness.variables(frame), "iteration").value(), "loop iteration");
            harness.engine().resume(stop.threadId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            equal(0, harness.awaitExit(), "debuggee exit code");
        }
    }

    private static DebugEngine.Variable variable(Map<String, DebugEngine.Variable> variables, String name) {
        DebugEngine.Variable variable = variables.get(name);
        if (variable == null) {
            throw new AssertionError("Debugger returned no variable named " + name + "; found " + variables.keySet());
        }
        return variable;
    }

    private static int mappedLine(int[] pairs, int sourceLine) {
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i] == sourceLine) {
                return pairs[i + 1];
            }
        }
        throw new AssertionError("No line mapping for displayed line " + sourceLine);
    }

    private static boolean containsMappedLine(int[] pairs, int sourceLine) {
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i] == sourceLine) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTrimmedLineWithMapping(
            String source,
            String expected,
            int[] mappings,
            boolean expectedMapped
    ) {
        String[] lines = source.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].trim().equals(expected)
                    && containsMappedLine(mappings, index + 1) == expectedMapped) {
                return true;
            }
        }
        return false;
    }

    private static ClassBytecodeSource classPathSource(ClassLoader loader) {
        return className -> {
            String resourceName = normalizeClassName(className) + ".class";
            try (InputStream input = loader.getResourceAsStream(resourceName)) {
                return input == null ? null : input.readAllBytes();
            }
        };
    }

    private static DebugEngine.Source decompiledSourceFor(Class<?> type) throws Exception {
        DecompilationResult result = new VineflowerDecompiler().decompile(
                type.getName(),
                classPathSource(type.getClassLoader())
        );
        return new DebugEngine.Source(
                URI.create("decompiled:///" + type.getName().replace('.', '/') + ".java"),
                type.getName(),
                result.source(),
                result.lineMap()
        );
    }

    private static int lineContaining(String source, String marker) {
        String[] lines = source.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].contains(marker)) {
                return index + 1;
            }
        }
        throw new AssertionError("Missing source marker " + marker + System.lineSeparator() + source);
    }

    private static String normalizeClassName(String className) {
        String normalized = className;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length());
        }
        return normalized.replace('.', '/');
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String subject) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(subject + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void contains(String actual, String expectedPart, String subject) {
        if (actual == null || !actual.contains(expectedPart)) {
            throw new AssertionError(subject + ": expected <" + actual + "> to contain <" + expectedPart + ">");
        }
    }

    public static final class Scenario {
        private final String id;
        private final String name;
        private final CheckedRunnable action;

        private Scenario(String id, String name, CheckedRunnable action) {
            this.id = id;
            this.name = name;
            this.action = action;
        }

        public String id() {
            return this.id;
        }

        public String name() {
            return this.name;
        }

        public void run() throws Exception {
            this.action.run();
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
