package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueLease;
import com.microsoft.java.debug.core.IEvaluatableBreakpoint;
import com.microsoft.java.debug.core.adapter.ICompletionsProvider;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.microsoft.java.debug.core.adapter.variables.StackFrameReference;
import com.microsoft.java.debug.core.adapter.variables.VariableProxy;
import com.microsoft.java.debug.core.protocol.Types;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Microsoft debug-core adapter for one Java expression, completion, and value previews. */
public final class RichJavaExpressionEngine implements IEvaluationProvider, ICompletionsProvider {
    private final DebuggerValueStore retainedValues = new DebuggerValueStore(
            reference -> this.debugContext.getRecyclableIdPool().removeObjectById(reference));
    private final DebuggerEvaluationRunner evaluations = new DebuggerEvaluationRunner(this.retainedValues::releaseHistory);
    private final DebuggerValuePreviewer valuePreviewer = new DebuggerValuePreviewer(this::evaluate);
    private final JavaExpressionEvaluator evaluator;
    private final JavaExpressionCompletion completion;
    private final CompiledFrameEvaluator compiled;
    private volatile IDebugAdapterContext debugContext;
    private volatile DebuggerTypeCatalog typeCatalog;
    private final Map<String, ActionBinding> actions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong stopRevision = new java.util.concurrent.atomic.AtomicLong();
    private volatile DebugEngine.BreakpointActionResult actionResult;
    private DebuggerValueLease actionValue = DebuggerValueLease.NONE;
    private java.util.function.Function<String, String> scriptSource = path -> {
        throw new IllegalStateException("Saved breakpoint scripts require the workspace script resolver");
    };
    private record ActionBinding(java.net.URI source, DebugEngine.SourceBreakpoint breakpoint) { }

    public void scriptSource(java.util.function.Function<String, String> source) { this.scriptSource = source; }
    public void invalidateContinuation() { this.stopRevision.incrementAndGet(); }
    public DebugEngine.BreakpointActionResult breakpointActionResult() { return this.actionResult; }
    public void clearBreakpointActions(java.net.URI source) {
        this.actions.entrySet().removeIf(entry -> entry.getValue().source().equals(source));
    }
    public String breakpointCondition(java.net.URI source, DebugEngine.SourceBreakpoint breakpoint) {
        if (breakpoint.action() == null) return breakpoint.condition();
        String key = "__tdBreakpoint" + java.util.UUID.randomUUID().toString().replace("-", "");
        this.actions.put(key, new ActionBinding(source, breakpoint));
        return key;
    }

    public RichJavaExpressionEngine(VariableNameResolver variableNameResolver, TypeScopeResolver typeScopeResolver) {
        this(variableNameResolver, typeScopeResolver, () -> null);
    }

    public RichJavaExpressionEngine(VariableNameResolver variableNameResolver, TypeScopeResolver typeScopeResolver,
                                    java.util.function.Supplier<String> classpath) {
        VariableNameResolver names = Objects.requireNonNull(variableNameResolver, "variableNameResolver");
        TypeScopeResolver scopes = Objects.requireNonNull(typeScopeResolver, "typeScopeResolver");
        this.evaluator = new JavaExpressionEvaluator(names, scopes, this::refreshStackFrames);
        this.compiled = new CompiledFrameEvaluator(classpath, names, scopes);
        this.completion = new JavaExpressionCompletion(this, this.evaluator, names, scopes);
    }

    public com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerEvaluation<?> activeEvaluation() {
        return this.evaluations.active();
    }
    public com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerEvaluation<?> evaluationOperation(String id) {
        return this.evaluations.operation(id);
    }
    public void onEvaluationChange(Runnable changed) { this.evaluations.onChange(changed); }

    public com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerEvaluation<DebugEngine.EvaluationResult> startEvaluation(
            String expression, int frameId) {
        if (this.evaluations.active() != null) throw new IllegalStateException("Debugger evaluation is still running");
        long generation = this.retainedValues.generation();
        StackFrame selected = requireFrame(frameId);
        ThreadReference thread = selected.thread();
        return this.evaluations.start(thread, () -> {
            Value value = evaluateFragment(expression, selected, selected.thisObject(), thread);
            var formatter = this.debugContext.getVariableFormatter();
            var options = formatter.getDefaultOptions();
            int reference = 0;
            int indexed = 0;
            if (value instanceof ObjectReference object) {
                reference = this.retainedValues.register(generation, DebuggerEvaluationRunner.currentId(), object,
                        () -> this.debugContext.getRecyclableIdPool().addObject(thread.uniqueID(),
                                valueProxy(thread, "eval", value, expression)));
                if (value instanceof com.sun.jdi.ArrayReference array) indexed = array.length();
            }
            return new DebugEngine.EvaluationResult(value instanceof com.sun.jdi.VoidValue ? "" : formatter.valueToString(value, options),
                    value instanceof com.sun.jdi.VoidValue ? "<void>" : value == null ? "null" : value.type().name(),
                    reference, indexed, scalar(value));
        });
    }
    private static VariableProxy valueProxy(ThreadReference thread, String scope, Value value, String expression) {
        var proxy = new VariableProxy(thread, scope, value, null, expression);
        proxy.setIndexedVariable(value instanceof com.sun.jdi.ArrayReference);
        return proxy;
    }

    private static DebugEngine.ScalarValue scalar(Value value) {
        if (value == null) return new DebugEngine.ScalarValue("null", null);
        if (value instanceof com.sun.jdi.VoidValue) return new DebugEngine.ScalarValue("void", null);
        if (value instanceof com.sun.jdi.StringReference text) return new DebugEngine.ScalarValue("string", text.value());
        if (value instanceof com.sun.jdi.BooleanValue flag) return new DebugEngine.ScalarValue("boolean", flag.value());
        if (value instanceof com.sun.jdi.CharValue character) return new DebugEngine.ScalarValue("char", String.valueOf(character.value()));
        if (value instanceof com.sun.jdi.LongValue number) return new DebugEngine.ScalarValue("long", Long.toString(number.value()));
        if (value instanceof com.sun.jdi.FloatValue number) return new DebugEngine.ScalarValue("float",
                Float.isFinite(number.value()) ? number.value() : Float.toString(number.value()));
        if (value instanceof com.sun.jdi.DoubleValue number) return new DebugEngine.ScalarValue("double",
                Double.isFinite(number.value()) ? number.value() : Double.toString(number.value()));
        if (value instanceof com.sun.jdi.PrimitiveValue number) return new DebugEngine.ScalarValue(number.type().name(), number.intValue());
        return null;
    }

    public DebuggerValueLease retainValue(int reference) { return this.retainedValues.retain(reference); }

    public void releaseValues() {
        synchronized (this.retainedValues) {
            this.retainedValues.clear();
            this.actionValue.close();
            this.actionValue = DebuggerValueLease.NONE;
            DebugEngine.BreakpointActionResult previous = this.actionResult;
            if (previous != null && previous.result() != null && previous.result().variablesReference() > 0) {
                var result = previous.result();
                this.actionResult = new DebugEngine.BreakpointActionResult(previous.source(),
                        new DebugEngine.EvaluationResult(result.value(), result.type(), 0, 0, result.scalar()), previous.error());
            }
        }
    }

    @Override
    public void close() {
        try {
            this.compiled.close();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public FrameVariables frameVariables(int frameId) {
        StackFrame frame = requireFrame(frameId);
        Map<String, DebugEngine.VariableKind> result = new LinkedHashMap<>();
        try {
            for (LocalVariable variable : frame.visibleVariables()) {
                result.put(
                        variable.name(),
                        variable.isArgument() ? DebugEngine.VariableKind.PARAMETER : DebugEngine.VariableKind.LOCAL
                );
            }
        } catch (AbsentInformationException ignored) {
            // JDI cannot distinguish parameters from locals without variable metadata.
        }
        if (frame.thisObject() != null) {
            result.put("this", DebugEngine.VariableKind.THIS);
        }
        Method method = frame.location().method();
        return new FrameVariables(method.name(), method.signature(), Map.copyOf(result));
    }

    public List<DebugEngine.ExpressionToken> expressionTokens(String expression, int frameId) {
        return JavaExpressionTokens.classify(expression, this.evaluator.context(requireFrame(frameId)));
    }

    public int arrayLength(int variablesReference) {
        Object reference = this.debugContext.getRecyclableIdPool().getObjectById(variablesReference);
        if (reference instanceof VariableProxy proxy && proxy.getProxiedVariable() instanceof com.sun.jdi.ArrayReference array) {
            return array.length();
        }
        throw new IllegalArgumentException("Variable reference does not identify an array: " + variablesReference);
    }

    public String valueType(int variablesReference) {
        Object reference = this.debugContext.getRecyclableIdPool().getObjectById(variablesReference);
        if (reference instanceof VariableProxy proxy && proxy.getProxiedVariable() instanceof ObjectReference value) {
            return value.type().name();
        }
        return null;
    }

    public CompletableFuture<DebugEngine.ValuePreview> preview(int variablesReference) {
        IDebugAdapterContext context = Objects.requireNonNull(this.debugContext, "debugContext");
        Object reference = context.getRecyclableIdPool().getObjectById(variablesReference);
        if (!(reference instanceof VariableProxy proxy)) {
            throw new IllegalArgumentException("Unknown debugger variable reference " + variablesReference);
        }
        if (!(proxy.getProxiedVariable() instanceof ObjectReference value)) {
            return CompletableFuture.completedFuture(DebugEngine.ValuePreview.NONE);
        }
        return this.valuePreviewer.preview(value, proxy.getThread());
    }

    @Override
    public void initialize(IDebugAdapterContext debugContext, Map<String, Object> options) {
        this.debugContext = Objects.requireNonNull(debugContext, "debugContext");
    }

    @Override
    public boolean isInEvaluation(ThreadReference thread) {
        return this.evaluations.isInEvaluation(thread);
    }

    @Override
    public CompletableFuture<Value> evaluate(String expression, ThreadReference thread, int depth) {
        return evaluateInternal(expression, thread, depth, null);
    }

    @Override
    public CompletableFuture<Value> evaluate(String expression, ObjectReference thisContext, ThreadReference thread) {
        return evaluateInternal(expression, thread, 0, thisContext);
    }

    private CompletableFuture<Value> evaluateInternal(
            String expression,
            ThreadReference thread,
            int depth,
            ObjectReference explicitThis
    ) {
        return this.evaluations.run(thread, () -> {
            StackFrame frame = thread.frame(depth);
            ObjectReference thisObject = explicitThis == null ? frame.thisObject() : explicitThis;
            return evaluateFragment(expression, frame, thisObject, thread);
        });
    }

    private Value evaluateFragment(String source, StackFrame frame, ObjectReference receiver, ThreadReference thread)
            throws Exception {
        boolean interpreted;
        try {
            JavaExpressionSupport.requireSupported(JavaExpressionEvaluator.parse(source));
            interpreted = true;
        } catch (IllegalArgumentException | UnsupportedOperationException compileRequired) {
            interpreted = false;
        }
        if (interpreted) return this.evaluator.evaluate(source, frame, receiver, thread);
        return this.compiled.evaluate(source, new JavaExpressionEvaluator.Context(frame, receiver, this.evaluator, thread));
    }

    @Override
    public CompletableFuture<Value> evaluateForBreakpoint(IEvaluatableBreakpoint breakpoint, ThreadReference thread) {
        if (breakpoint.containsLogpointExpression()) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "Logpoint expression evaluation is not implemented"
            ));
        }
        String conditionKey = breakpoint.getCondition();
        ActionBinding binding = this.actions.get(conditionKey);
        long revision = this.stopRevision.get();
        long started = System.nanoTime();
        long generation = this.retainedValues.generation();
        return this.evaluations.<Value>start(thread, () -> {
            String source = binding == null ? conditionKey : binding.breakpoint().action().source();
            DebugEngine.EvaluationResult formatted = null;
            try {
                StackFrame frame = thread.frame(0);
                String condition = binding == null ? conditionKey : binding.breakpoint().condition();
                Value accepted = condition == null || condition.isBlank() ? thread.virtualMachine().mirrorOf(true)
                        : evaluateFragment(condition, frame, frame.thisObject(), thread);
                if (!(accepted instanceof com.sun.jdi.BooleanValue booleanValue)) {
                    throw new IllegalArgumentException("Breakpoint condition must return a boolean");
                }
                boolean stayPaused = booleanValue.value();
                if (stayPaused && binding != null) {
                    DebuggerEvaluationRunner.checkpoint();
                    DebugEngine.BreakpointAction action = binding.breakpoint().action();
                    source = action.script() == null ? action.source() : action.script();
                    if (action.script() != null) source = this.scriptSource.apply(action.script());
                    frame = thread.frame(0);
                    Value result = evaluateFragment(source, frame, frame.thisObject(), thread);
                    DebugEngine.ScalarValue captured = scalar(result);
                    int reference = 0;
                    if (result instanceof ObjectReference object && (!action.continueOnSuccess() || captured == null)) {
                        reference = this.retainedValues.register(generation, DebuggerEvaluationRunner.currentId(), object,
                                () -> this.debugContext.getRecyclableIdPool().addObject(thread.uniqueID(),
                                        valueProxy(thread, "breakpoint action", result, null)));
                    }
                    var formatter = this.debugContext.getVariableFormatter();
                    String display = result instanceof com.sun.jdi.VoidValue ? ""
                            : formatter.valueToString(result, formatter.getDefaultOptions());
                    formatted = new DebugEngine.EvaluationResult(display,
                            result == null ? "null" : result instanceof com.sun.jdi.VoidValue ? "<void>" : result.type().name(),
                            reference, result instanceof com.sun.jdi.ArrayReference array ? array.length() : 0, captured);
                    if (action.continueOnSuccess() && captured == null) {
                        throw new IllegalStateException("Continue-on-success requires a scalar action result; object results remain paused for inspection");
                    }
                    stayPaused = !action.continueOnSuccess();
                }
                DebuggerEvaluationRunner.checkpoint();
                if ((System.nanoTime() - started) / 1_000_000 >= 5_000
                        || revision != this.stopRevision.get() || !Objects.equals(conditionKey, breakpoint.getCondition())
                        || binding != null && this.actions.get(conditionKey) != binding) {
                    throw new IllegalStateException("Breakpoint evaluation was slow or invalidated; execution remains paused");
                }
                if (formatted != null) retainActionResult(generation, new DebugEngine.BreakpointActionResult(source, formatted, null));
                return thread.virtualMachine().mirrorOf(stayPaused);
            } catch (Exception failure) {
                if (binding != null) retainActionResult(generation, new DebugEngine.BreakpointActionResult(source, formatted, failure.toString()));
                throw failure;
            }
        }).completion();
    }

    private void retainActionResult(long generation, DebugEngine.BreakpointActionResult result) {
        synchronized (this.retainedValues) {
            if (generation != this.retainedValues.generation()) return;
            DebuggerValueLease retained = result.result() == null ? DebuggerValueLease.NONE
                    : this.retainedValues.retain(result.result().variablesReference());
            this.actionValue.close();
            this.actionValue = retained;
            this.actionResult = result;
        }
    }

    @Override
    public CompletableFuture<Value> invokeMethod(
            ObjectReference thisContext,
            String methodName,
            String methodSignature,
            Value[] arguments,
            ThreadReference thread,
            boolean invokeSuper
    ) {
        return this.evaluations.run(thread, () -> this.evaluator.invokeMethod(
                thisContext, methodName, methodSignature, arguments, thread, invokeSuper
        ));
    }

    @Override
    public void clearState(ThreadReference thread) {
        this.evaluations.clearState(thread);
        this.typeCatalog = null;
    }

    @Override
    public List<Types.CompletionItem> codeComplete(StackFrame frame, String snippet, int line, int column) {
        return this.completion.codeComplete(frame, snippet, line, column);
    }

    synchronized DebuggerTypeCatalog typeCatalog(VirtualMachine vm) {
        if (this.typeCatalog == null || !this.typeCatalog.belongsTo(vm)) {
            this.typeCatalog = DebuggerTypeCatalog.capture(vm);
        }
        return this.typeCatalog;
    }

    private StackFrame requireFrame(int frameId) {
        IDebugAdapterContext context = Objects.requireNonNull(this.debugContext, "debugContext");
        Object reference = context.getRecyclableIdPool().getObjectById(frameId);
        if (!(reference instanceof StackFrameReference frameReference)) {
            throw new IllegalArgumentException("Unknown debugger stack frame " + frameId);
        }
        StackFrame frame = context.getStackFrameManager().getStackFrame(frameReference);
        if (frame == null) {
            throw new IllegalStateException("Debugger stack frame " + frameId + " is no longer available");
        }
        return frame;
    }

    private void refreshStackFrames(ThreadReference thread) {
        IDebugAdapterContext context = this.debugContext;
        if (context == null || thread == null) {
            return;
        }
        try {
            context.getStackFrameManager().reloadStackFrames(thread);
        } catch (RuntimeException ignored) {
            // The debuggee may terminate while an invocation is unwinding.
        }
    }

    @FunctionalInterface
    public interface VariableNameResolver {
        String displayedName(String binaryName, String methodName, String methodDescriptor, String runtimeName);
    }

    @FunctionalInterface
    public interface TypeScopeResolver {
        DebuggerTypeScope scope(String binaryName);
    }

    public record FrameVariables(
            String methodName,
            String methodDescriptor,
            Map<String, DebugEngine.VariableKind> kinds
    ) {
    }

}
