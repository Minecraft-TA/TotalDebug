package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Microsoft debug-core adapter for one Java expression, completion, and value previews. */
public final class RichJavaExpressionEngine implements IEvaluationProvider, ICompletionsProvider {
    private final DebuggerEvaluationRunner evaluations = new DebuggerEvaluationRunner(() -> this.debugContext);
    private final DebuggerValuePreviewer valuePreviewer = new DebuggerValuePreviewer(this::evaluate);
    private final JavaExpressionEvaluator evaluator;
    private final JavaExpressionCompletion completion;
    private volatile IDebugAdapterContext debugContext;
    private volatile DebuggerTypeCatalog typeCatalog;

    public RichJavaExpressionEngine(VariableNameResolver variableNameResolver, TypeScopeResolver typeScopeResolver) {
        VariableNameResolver names = Objects.requireNonNull(variableNameResolver, "variableNameResolver");
        TypeScopeResolver scopes = Objects.requireNonNull(typeScopeResolver, "typeScopeResolver");
        this.evaluator = new JavaExpressionEvaluator(names, scopes, this::refreshStackFrames);
        this.completion = new JavaExpressionCompletion(this, this.evaluator, names, scopes);
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
            return this.evaluator.evaluate(expression, frame, thisObject, thread);
        });
    }

    @Override
    public CompletableFuture<Value> evaluateForBreakpoint(IEvaluatableBreakpoint breakpoint, ThreadReference thread) {
        if (breakpoint.containsLogpointExpression()) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "Logpoint expression evaluation is not implemented"
            ));
        }
        return evaluateInternal(breakpoint.getCondition(), thread, 0, null);
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
