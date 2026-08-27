package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.microsoft.java.debug.core.IEvaluatableBreakpoint;
import com.microsoft.java.debug.core.adapter.ICompletionsProvider;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.microsoft.java.debug.core.adapter.variables.StackFrameReference;
import com.microsoft.java.debug.core.protocol.Types;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.ClassType;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.InvocationException;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TypeLiteral;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerJdiMembers.fields;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerJdiMembers.findField;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerJdiMembers.isAssignable;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerJdiMembers.isAssignableName;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerJdiMembers.isPrimitive;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerJdiMembers.methods;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerJdiMembers.resolveType;

/** One JDI-backed expression engine used for evaluation and runtime completion. */
final class RichJavaExpressionEngine implements IEvaluationProvider, ICompletionsProvider {
    private static final List<String> KEYWORDS = List.of("true", "false", "null", "this", "super");
    private final VariableNameResolver variableNameResolver;
    private final DebuggerEvaluationLifecycle lifecycle = new DebuggerEvaluationLifecycle();
    private volatile IDebugAdapterContext debugContext;

    RichJavaExpressionEngine(VariableNameResolver variableNameResolver) {
        this.variableNameResolver = Objects.requireNonNull(variableNameResolver, "variableNameResolver");
    }

    Map<String, DebugEngine.VariableKind> variableKinds(int frameId) {
        IDebugAdapterContext context = Objects.requireNonNull(this.debugContext, "debugContext");
        Object reference = context.getRecyclableIdPool().getObjectById(frameId);
        if (!(reference instanceof StackFrameReference frameReference)) {
            throw new IllegalArgumentException("Unknown debugger stack frame " + frameId);
        }
        StackFrame frame = context.getStackFrameManager().getStackFrame(frameReference);
        if (frame == null) {
            throw new IllegalStateException("Debugger stack frame " + frameId + " is no longer available");
        }
        Map<String, DebugEngine.VariableKind> result = new LinkedHashMap<>();
        try {
            for (LocalVariable variable : frame.visibleVariables()) {
                result.put(
                        variable.name(),
                        variable.isArgument()
                                ? DebugEngine.VariableKind.PARAMETER
                                : DebugEngine.VariableKind.LOCAL
                );
            }
        } catch (AbsentInformationException exception) {
            throw new IllegalStateException("Debugger variable metadata is unavailable for frame " + frameId, exception);
        }
        if (frame.thisObject() != null) {
            result.put("this", DebugEngine.VariableKind.THIS);
        }
        return Map.copyOf(result);
    }

    @Override
    public void initialize(IDebugAdapterContext debugContext, Map<String, Object> options) {
        this.debugContext = debugContext;
    }

    @Override
    public boolean isInEvaluation(ThreadReference thread) {
        return this.lifecycle.isInEvaluation(thread);
    }

    @Override
    public CompletableFuture<Value> evaluate(String expression, ThreadReference thread, int depth) {
        return evaluateInternal(expression, thread, depth);
    }

    private CompletableFuture<Value> evaluateInternal(
            String expression,
            ThreadReference thread,
            int depth
    ) {
        return CompletableFuture.supplyAsync(() -> {
            begin(thread);
            try {
                StackFrame frame = thread.frame(depth);
                return evaluate(parse(expression), new Context(frame, frame.thisObject(), this.variableNameResolver,
                        this, thread)).value();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            } finally {
                end(thread);
            }
        });
    }

    @Override
    public CompletableFuture<Value> evaluate(String expression, ObjectReference thisContext, ThreadReference thread) {
        return CompletableFuture.supplyAsync(() -> {
            begin(thread);
            try {
                return evaluate(parse(expression), new Context(thread.frame(0), thisContext, this.variableNameResolver,
                        this, thread)).value();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            } finally {
                end(thread);
            }
        });
    }

    @Override
    public CompletableFuture<Value> evaluateForBreakpoint(IEvaluatableBreakpoint breakpoint, ThreadReference thread) {
        if (breakpoint.containsLogpointExpression()) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "Logpoint expression evaluation is not implemented"
            ));
        }
        return evaluateInternal(breakpoint.getCondition(), thread, 0);
    }

    @Override
    public CompletableFuture<Value> invokeMethod(
            ObjectReference thisContext,
            String methodName,
            String methodSignature,
            Value[] args,
            ThreadReference thread,
            boolean invokeSuper
    ) {
        return CompletableFuture.supplyAsync(() -> {
            begin(thread);
            try {
                if (thisContext == null) {
                    throw new IllegalArgumentException("An object is required to invoke " + methodName);
                }
                Method method = findMethod(thisContext.referenceType(), methodName, methodSignature, invokeSuper);
                if (method == null) {
                    throw new IllegalArgumentException("Unknown method " + methodName + methodSignature);
                }
                Context context = new Context(thread.frame(0), thisContext, this.variableNameResolver,
                        this, thread);
                return invoke(thisContext, method, List.of(args == null ? new Value[0] : args), context, invokeSuper);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            } finally {
                end(thread);
            }
        });
    }

    @Override
    public void clearState(ThreadReference thread) {
        this.lifecycle.clearState(thread);
    }

    @Override
    public List<Types.CompletionItem> codeComplete(StackFrame frame, String snippet, int line, int column) {
        int offset = DebuggerCompletionRange.offsetOf(snippet, line, column);
        DebuggerCompletionRange range = DebuggerCompletionRange.around(snippet, offset);
        List<DebuggerCompletionProposal> proposals = complete(frame, snippet, range, offset);
        List<Types.CompletionItem> result = new ArrayList<>(proposals.size());
        for (DebuggerCompletionProposal proposal : proposals) {
            Types.CompletionItem item = new Types.CompletionItem(proposal.label(), proposal.insertionText());
            item.type = proposal.kind().name().toLowerCase(Locale.ROOT);
            item.sortText = DebuggerCompletionWire.encode(proposal);
            item.start = proposal.replacementStart();
            item.number = proposal.replacementEnd() - proposal.replacementStart();
            result.add(item);
        }
        return result;
    }

    private List<DebuggerCompletionProposal> complete(
            StackFrame frame,
            String snippet,
            DebuggerCompletionRange range,
            int offset
    ) {
        Map<String, DebuggerCompletionProposal> result = new LinkedHashMap<>();
        try {
            if (range.memberAccess()) {
                String ownerText = range.ownerExpression(snippet).trim();
                if (!ownerText.isEmpty()) {
                    CompletionOwner owner = resolveCompletionOwner(parse(ownerText),
                            new Context(frame, frame.thisObject(), this.variableNameResolver, this, frame.thread()));
                    addMembers(result, owner, range);
                }
            } else {
                addLocalsAndFields(result, frame, range);
            }
        } catch (Exception ignored) {
            // Incomplete expressions are expected while the user is typing.
        }
        if (!range.memberAccess()) {
            for (String keyword : KEYWORDS) {
                if ((keyword.equals("this") || keyword.equals("super")) && frame.thisObject() == null) {
                    continue;
                }
                add(result, new DebuggerCompletionProposal(
                        keyword, keyword, DebuggerCompletionProposal.Kind.KEYWORD,
                        keyword.equals("null") ? "null literal" : "Java expression keyword",
                        range.start(), range.end(), keyword.length(), 80
                ));
            }
        }
        return result.values().stream()
                .filter(proposal -> proposal.label().toLowerCase(Locale.ROOT)
                        .startsWith(range.prefix().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparingInt(DebuggerCompletionProposal::rank)
                        .thenComparing(DebuggerCompletionProposal::label, String.CASE_INSENSITIVE_ORDER))
                .limit(64)
                .toList();
    }

    private void addLocalsAndFields(
            Map<String, DebuggerCompletionProposal> result,
            StackFrame frame,
            DebuggerCompletionRange range
    ) throws Exception {
        String binaryName = frame.location().declaringType().name();
        for (LocalVariable local : frame.visibleVariables()) {
            String name = this.variableNameResolver.displayedName(binaryName, local.name());
            add(result, proposal(name, name, DebuggerCompletionProposal.Kind.VARIABLE,
                    local.typeName(), range, 10));
        }
        if (frame.thisObject() != null) {
            addFields(result, frame.thisObject().referenceType(), range, 20, false);
        }
        addFields(result, frame.location().declaringType(), range, 25, true);
        if (frame.thisObject() != null) {
            addMethods(result, frame.thisObject().referenceType(), range, 35, false);
        }
    }

    /** Resolves only static/runtime metadata. This method must never invoke a target method. */
    private static CompletionOwner resolveCompletionOwner(Expression expression, Context context) throws Exception {
        if (expression instanceof ParenthesizedExpression parenthesized) {
            return resolveCompletionOwner(parenthesized.getExpression(), context);
        }
        if (expression instanceof ThisExpression) {
            return new CompletionOwner(context.frame().location().declaringType(), false, false);
        }
        if (expression instanceof TypeLiteral typeLiteral) {
            return new CompletionOwner(resolveType(typeLiteral.getType().toString(), context.vm()), true, false);
        }
        if (expression instanceof CastExpression cast) {
            return new CompletionOwner(resolveType(cast.getType().toString(), context.vm()), false, false);
        }
        if (expression instanceof ArrayAccess access) {
            CompletionOwner array = resolveCompletionOwner(access.getArray(), context);
            return array != null && array.array() ? new CompletionOwner(null, false, true) : null;
        }
        if (expression instanceof SimpleName name) {
            return resolveSimpleCompletionOwner(name.getIdentifier(), context);
        }
        if (expression instanceof QualifiedName name) {
            ReferenceType type = resolveType(name.getFullyQualifiedName(), context.vm());
            if (type != null) {
                return new CompletionOwner(type, true, false);
            }
            CompletionOwner qualifier = resolveCompletionOwner(name.getQualifier(), context);
            return fieldCompletionOwner(qualifier, name.getName().getIdentifier(), context);
        }
        if (expression instanceof FieldAccess access) {
            return fieldCompletionOwner(resolveCompletionOwner(access.getExpression(), context),
                    access.getName().getIdentifier(), context);
        }
        if (expression instanceof SuperFieldAccess access) {
            ReferenceType superclass = lexicalSuperclass(context);
            return fieldCompletionOwner(new CompletionOwner(superclass, false, false),
                    access.getName().getIdentifier(), context);
        }
        if (expression instanceof MethodInvocation invocation) {
            CompletionOwner receiver = invocation.getExpression() == null
                    ? new CompletionOwner(context.frame().location().declaringType(), false, false)
                    : resolveCompletionOwner(invocation.getExpression(), context);
            return methodCompletionOwner(receiver, invocation.getName().getIdentifier(), invocation.arguments(), context);
        }
        if (expression instanceof SuperMethodInvocation invocation) {
            return methodCompletionOwner(new CompletionOwner(lexicalSuperclass(context), false, false),
                    invocation.getName().getIdentifier(), invocation.arguments(), context);
        }
        return null;
    }

    private static CompletionOwner resolveSimpleCompletionOwner(String name, Context context) throws Exception {
        StackFrame frame = context.frame();
        for (LocalVariable local : frame.visibleVariables()) {
            if (local.name().equals(name)
                    || context.variableNameResolver().displayedName(frame.location().declaringType().name(), local.name()).equals(name)) {
                ReferenceType type = resolveType(local.typeName(), context.vm());
                return type == null && local.typeName().endsWith("[]")
                        ? new CompletionOwner(null, false, true)
                        : new CompletionOwner(type, false, false);
            }
        }
        Field field = findField(frame.location().declaringType(), name, false);
        if (field != null) {
            return new CompletionOwner(resolveType(field.typeName(), context.vm()), false, false);
        }
        ReferenceType type = resolveType(name, context.vm());
        return type == null ? null : new CompletionOwner(type, true, false);
    }

    private static CompletionOwner fieldCompletionOwner(CompletionOwner owner, String name, Context context) {
        if (owner == null || owner.array() || owner.type() == null) {
            return null;
        }
        Field field = findField(owner.type(), name, owner.typeLiteral());
        return field == null ? null : new CompletionOwner(resolveType(field.typeName(), context.vm()), false, false);
    }

    private static CompletionOwner methodCompletionOwner(
            CompletionOwner owner,
            String name,
            List<?> arguments,
            Context context
    ) throws Exception {
        if (owner == null || owner.array() || owner.type() == null) {
            return null;
        }
        List<Expression> expressions = arguments.stream().map(Expression.class::cast).toList();
        List<ScoredMethod> compatible = methods(owner.type()).stream()
                .filter(candidate -> candidate.name().equals(name))
                .filter(candidate -> !owner.typeLiteral() || candidate.isStatic())
                .map(candidate -> new ScoredMethod(candidate,
                        staticCompatibility(candidate, expressions, context)))
                .filter(candidate -> candidate.score() >= 0)
                .sorted(Comparator.comparingInt(ScoredMethod::score)
                        .thenComparing(candidate -> candidate.method().isVarArgs())
                        .thenComparing(candidate -> candidate.method().signature()))
                .toList();
        if (compatible.isEmpty()) return null;
        int bestScore = compatible.getFirst().score();
        List<ScoredMethod> best = compatible.stream()
                .takeWhile(candidate -> candidate.score() == bestScore)
                .toList();
        List<ScoredMethod> mostSpecific = best.stream()
                .filter(candidate -> best.stream().noneMatch(other ->
                        other != candidate && DebuggerOverloadResolver.moreSpecific(
                                other.method(), candidate.method(), context.vm())))
                .toList();
        Method method = mostSpecific.size() == 1 ? mostSpecific.getFirst().method() : null;
        return method == null ? null
                : new CompletionOwner(resolveType(method.returnTypeName(), context.vm()), false, false);
    }

    private static int staticCompatibility(Method method, List<Expression> arguments, Context context) {
        try {
        List<String> parameters = method.argumentTypeNames();
        if (!method.isVarArgs() && parameters.size() != arguments.size()) return -1;
        if (method.isVarArgs() && arguments.size() < parameters.size() - 1) return -1;
        boolean directArray = method.isVarArgs() && arguments.size() == parameters.size()
                && staticValueCompatibility(staticTypeName(arguments.getLast(), context), parameters.getLast(), context) >= 0;
        int score = method.isVarArgs() && !directArray ? 100 : 0;
        for (int i = 0; i < arguments.size(); i++) {
            if (directArray && i == parameters.size() - 1) continue;
            String parameter = parameters.get(Math.min(i, parameters.size() - 1));
            if (method.isVarArgs() && i >= parameters.size() - 1) {
                parameter = parameter.substring(0, parameter.length() - 2);
            }
            int current = staticValueCompatibility(staticTypeName(arguments.get(i), context), parameter, context);
            if (current < 0) return -1;
            score += current;
        }
        return score;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static int staticValueCompatibility(String source, String target, Context context) {
        if (source == null) return 20;
        if (source.equals("<null>")) return isPrimitive(target) ? -1 : 20;
        DebuggerPrimitiveKind sourcePrimitive = DebuggerPrimitiveKind.fromPrimitiveName(source);
        DebuggerPrimitiveKind sourceWrapper = DebuggerPrimitiveKind.fromTypeName(source);
        DebuggerPrimitiveKind targetPrimitive = DebuggerPrimitiveKind.fromPrimitiveName(target);
        if (targetPrimitive != null) {
            if (sourcePrimitive != null) return sourcePrimitive.wideningCostTo(targetPrimitive);
            if (sourceWrapper != null) {
                int widening = sourceWrapper.wideningCostTo(targetPrimitive);
                return widening < 0 ? -1 : 10 + widening;
            }
            return -1;
        }
        if (sourcePrimitive != null) {
            String boxed = sourcePrimitive.boxedName();
            return boxed.equals(target) ? 10
                    : isAssignableName(boxed, target, context.vm()) ? 12 : -1;
        }
        return source.equals(target) ? 0 : isAssignableName(source, target, context.vm()) ? 2 : -1;
    }

    private static String staticTypeName(Expression expression, Context context) {
        if (expression instanceof ParenthesizedExpression parenthesized) {
            return staticTypeName(parenthesized.getExpression(), context);
        }
        if (expression instanceof BooleanLiteral) return "boolean";
        if (expression instanceof CharacterLiteral) return "char";
        if (expression instanceof StringLiteral) return "java.lang.String";
        if (expression instanceof NumberLiteral literal) {
            String token = literal.getToken().replace("_", "").toLowerCase(Locale.ROOT);
            if (token.endsWith("l")) return "long";
            if (token.endsWith("f")) return "float";
            if (token.endsWith("d") || token.contains(".") || token.contains("e")) return "double";
            return "int";
        }
        if (expression instanceof NullLiteral) return "<null>";
        if (expression instanceof CastExpression cast) return cast.getType().toString();
        try {
            CompletionOwner resolved = resolveCompletionOwner(expression, context);
            return resolved == null || resolved.type() == null ? null : resolved.type().name();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ReferenceType lexicalSuperclass(Context context) {
        ReferenceType declaringType = context.frame().location().declaringType();
        return declaringType instanceof ClassType classType ? classType.superclass() : null;
    }

    private void addMembers(Map<String, DebuggerCompletionProposal> result, CompletionOwner owner,
                            DebuggerCompletionRange range) {
        if (owner.array()) {
            add(result, proposal("length", "length", DebuggerCompletionProposal.Kind.FIELD,
                    "int", range, 20));
            return;
        }
        if (owner.type() != null) {
            addFields(result, owner.type(), range, 20, owner.typeLiteral());
            addMethods(result, owner.type(), range, 30, owner.typeLiteral());
        }
    }

    private void addFields(
            Map<String, DebuggerCompletionProposal> result,
            ReferenceType type,
            DebuggerCompletionRange range,
            int rank,
            boolean staticOnly
    ) {
        for (Field field : fields(type)) {
            if (staticOnly && !field.isStatic()) {
                continue;
            }
            add(result, proposal(field.name(), field.name(),
                    field.isStatic() ? DebuggerCompletionProposal.Kind.CONSTANT : DebuggerCompletionProposal.Kind.FIELD,
                    field.typeName(), range, rank));
        }
    }

    private void addMethods(Map<String, DebuggerCompletionProposal> result, ReferenceType type,
                            DebuggerCompletionRange range, int rank, boolean staticOnly) {
        for (Method method : methods(type)) {
            if (staticOnly && !method.isStatic()) {
                continue;
            }
            String insertion = method.name() + "()";
            int caret = insertion.length() - 1;
            add(result, new DebuggerCompletionProposal(
                    method.name() + methodLabel(method), insertion, DebuggerCompletionProposal.Kind.METHOD,
                    method.returnTypeName(), range.start(), range.end(), caret, rank
            ));
        }
    }

    private static DebuggerCompletionProposal proposal(
            String label,
            String insertion,
            DebuggerCompletionProposal.Kind kind,
            String detail,
            DebuggerCompletionRange range,
            int rank
    ) {
        return new DebuggerCompletionProposal(label, insertion, kind, detail,
                range.start(), range.end(), insertion.length(), rank);
    }

    private static void add(Map<String, DebuggerCompletionProposal> result, DebuggerCompletionProposal proposal) {
        result.merge(proposal.label(), proposal, (oldValue, newValue) ->
                newValue.rank() < oldValue.rank() ? newValue : oldValue);
    }

    private static String methodLabel(Method method) {
        return "(" + String.join(", ", method.argumentTypeNames()) + ")";
    }

    private static EvalValue evaluate(Expression expression, Context context) throws Exception {
        if (expression instanceof ParenthesizedExpression parenthesized) {
            return evaluate(parenthesized.getExpression(), context);
        }
        if (expression instanceof BooleanLiteral literal) {
            return value(context.vm().mirrorOf(literal.booleanValue()));
        }
        if (expression instanceof CharacterLiteral literal) {
            return value(context.vm().mirrorOf(literal.charValue()));
        }
        if (expression instanceof StringLiteral literal) {
            return value(context.vm().mirrorOf(literal.getLiteralValue()));
        }
        if (expression instanceof NumberLiteral literal) {
            return value(mirrorNumber(context.vm(), literal.getToken()));
        }
        if (expression instanceof NullLiteral) {
            return value(null);
        }
        if (expression instanceof ThisExpression) {
            if (context.thisObject() == null) {
                throw new IllegalArgumentException("'this' is unavailable in the selected frame");
            }
            return value(context.thisObject());
        }
        if (expression instanceof SimpleName name) {
            return simpleName(name.getIdentifier(), context);
        }
        if (expression instanceof QualifiedName name) {
            return qualifiedName(name, context);
        }
        if (expression instanceof FieldAccess access) {
            return field(evaluate(access.getExpression(), context), access.getName().getIdentifier(), context);
        }
        if (expression instanceof SuperFieldAccess access) {
            if (context.thisObject() == null) {
                throw new IllegalArgumentException("'super' is unavailable in the selected frame");
            }
            ReferenceType declaringType = context.frame().location().declaringType();
            if (!(declaringType instanceof ClassType classType) || classType.superclass() == null) {
                throw new IllegalArgumentException("No superclass is available for 'super'");
            }
            Field superField = findField(classType.superclass(), access.getName().getIdentifier(), false);
            if (superField == null) {
                throw new IllegalArgumentException("Unknown superclass field " + access.getName());
            }
            return value(context.thisObject().getValue(superField));
        }
        if (expression instanceof ArrayAccess access) {
            EvalValue arrayValue = evaluate(access.getArray(), context);
            if (!(arrayValue.value() instanceof ArrayReference array)) {
                throw new IllegalArgumentException("Array access requires an array value");
            }
            int index = toNumber(evaluate(access.getIndex(), context).value()).intValue();
            Type componentType = ((ArrayType) array.referenceType()).componentType();
            return value(array.getValue(index), componentType instanceof ReferenceType referenceType ? referenceType : null);
        }
        if (expression instanceof PrefixExpression prefix) {
            return prefix(prefix, context);
        }
        if (expression instanceof InfixExpression infix) {
            return infix(infix, context);
        }
        if (expression instanceof ConditionalExpression conditional) {
            return toBoolean(evaluate(conditional.getExpression(), context).value())
                    ? evaluate(conditional.getThenExpression(), context)
                    : evaluate(conditional.getElseExpression(), context);
        }
        if (expression instanceof MethodInvocation invocation) {
            return methodInvocation(invocation, context);
        }
        if (expression instanceof SuperMethodInvocation invocation) {
            return superMethodInvocation(invocation, context);
        }
        if (expression instanceof CastExpression cast) {
            return cast(evaluate(cast.getExpression(), context), cast.getType().toString(), context);
        }
        if (expression instanceof InstanceofExpression instanceofExpression) {
            EvalValue candidate = evaluate(instanceofExpression.getLeftOperand(), context);
            ReferenceType target = resolveType(instanceofExpression.getRightOperand().toString(), context.vm());
            return value(context.vm().mirrorOf(candidate.value() instanceof ObjectReference object
                    && target != null && isAssignable(object.referenceType(), target)));
        }
        if (expression instanceof TypeLiteral typeLiteral) {
            ReferenceType type = resolveType(typeLiteral.getType().toString(), context.vm());
            if (type == null) {
                throw new IllegalArgumentException("Unknown type " + typeLiteral.getType());
            }
            return type(type);
        }
        throw new UnsupportedOperationException(
                "Expression type is not implemented: " + expression.getClass().getSimpleName()
        );
    }

    private static EvalValue simpleName(String name, Context context) throws Exception {
        if (context.frame() != null) {
            LocalVariable local = context.frame().visibleVariableByName(name);
            if (local == null) {
                String binaryName = context.frame().location().declaringType().name();
                for (LocalVariable candidate : context.frame().visibleVariables()) {
                    if (context.variableNameResolver().displayedName(binaryName, candidate.name()).equals(name)) {
                        if (local != null) {
                            throw new IllegalArgumentException("Displayed variable name is ambiguous in the selected frame: " + name);
                        }
                        local = candidate;
                    }
                }
            }
            if (local != null) {
                return value(context.frame().getValue(local), resolveType(local.typeName(), context.vm()));
            }
        }
        if (context.thisObject() != null) {
            Field field = findField(context.thisObject().referenceType(), name, false);
            if (field != null) {
            return value(context.thisObject().getValue(field), resolveType(field.typeName(), context.vm()));
            }
        }
        if (context.frame() != null) {
            Field field = findField(context.frame().location().declaringType(), name, true);
            if (field != null) {
            return value(context.frame().location().declaringType().getValue(field),
                    resolveType(field.typeName(), context.vm()));
            }
        }
        ReferenceType type = resolveType(name, context.vm());
        if (type != null) {
            return type(type);
        }
        throw new IllegalArgumentException("Unknown variable or field: " + name);
    }

    private static EvalValue qualifiedName(QualifiedName name, Context context) throws Exception {
        ReferenceType fullType = resolveType(name.getFullyQualifiedName(), context.vm());
        if (fullType != null) {
            return type(fullType);
        }
        EvalValue qualifier = evaluate(name.getQualifier(), context);
        if (qualifier.typeLiteral() && qualifier.type() != null) {
            ReferenceType nested = resolveType(qualifier.type().name() + "$" + name.getName(), context.vm());
            if (nested != null) return type(nested);
        }
        return field(qualifier, name.getName().getIdentifier(), context);
    }

    private static EvalValue field(EvalValue owner, String name, Context context) {
        if (owner.typeLiteral()) {
            Field field = findField(owner.type(), name, true);
            if (field == null) {
                throw new IllegalArgumentException("Unknown static field " + owner.type().name() + "." + name);
            }
            return value(owner.type().getValue(field), resolveType(field.typeName(), context.vm()));
        }
        if (owner.value() == null) {
            throw new IllegalArgumentException("Cannot read field '" + name + "' from null");
        }
        if (owner.value() instanceof ArrayReference array && name.equals("length")) {
            return value(array.virtualMachine().mirrorOf(array.length()),
                    resolveType("int", context.vm()));
        }
        if (!(owner.value() instanceof ObjectReference object)) {
            throw new IllegalArgumentException("Field access requires an object value");
        }
        Field field = findField(owner.type() != null ? owner.type() : object.referenceType(), name, false);
        if (field == null) {
            throw new IllegalArgumentException("Unknown field " + object.referenceType().name() + "." + name);
        }
        return value(object.getValue(field), resolveType(field.typeName(), context.vm()));
    }

    private static EvalValue methodInvocation(MethodInvocation invocation, Context context) throws Exception {
        List<Value> args = new ArrayList<>();
        for (Object argument : invocation.arguments()) {
            args.add(evaluate((Expression) argument, context).value());
        }
        EvalValue receiver;
        if (invocation.getExpression() == null) {
            receiver = context.thisObject() == null
                    ? type(context.frame().location().declaringType())
                    : value(context.thisObject());
        } else {
            receiver = evaluate(invocation.getExpression(), context);
        }
        return invoke(receiver, invocation.getName().getIdentifier(), args, context, false, null);
    }

    private static EvalValue superMethodInvocation(SuperMethodInvocation invocation, Context context) throws Exception {
        List<Value> args = new ArrayList<>();
        for (Object argument : invocation.arguments()) {
            args.add(evaluate((Expression) argument, context).value());
        }
        return invoke(value(context.thisObject()), invocation.getName().getIdentifier(), args, context, true,
                lexicalSuperclass(context));
    }

    private static EvalValue invoke(
            EvalValue receiver,
            String name,
            List<Value> args,
            Context context,
            boolean invokeSuper,
            ReferenceType lookupType
    ) throws Exception {
        if (receiver.value() == null && receiver.type() == null) {
            throw new IllegalArgumentException("Cannot invoke " + name + " on null");
        }
        ReferenceType type = lookupType != null
                ? lookupType
                : receiver.type() != null
                ? receiver.type()
                : ((ObjectReference) receiver.value()).referenceType();
        Method method = DebuggerOverloadResolver.selectMethod(type, name, args, receiver.typeLiteral());
        if (method == null) {
            throw new IllegalArgumentException("No compatible overload for " + type.name() + "." + name);
        }
        List<Value> converted = DebuggerOverloadResolver.convertArguments(args, method, context);
        Value result;
        try {
            int invocationOptions = ObjectReference.INVOKE_SINGLE_THREADED;
            if (receiver.typeLiteral()) {
                if (!method.isStatic()) {
                    throw new IllegalArgumentException("Type-qualified invocation requires a static method: " + name);
                }
                if (receiver.type() instanceof ClassType classType) {
                    result = classType.invokeMethod(context.thread(), method, converted, invocationOptions);
                } else if (receiver.type() instanceof InterfaceType interfaceType) {
                    result = interfaceType.invokeMethod(context.thread(), method, converted, invocationOptions);
                } else {
                    throw new IllegalArgumentException("Type-qualified invocation requires a reference type: " + name);
                }
            } else {
                ObjectReference object = (ObjectReference) receiver.value();
                if (method.isStatic() && method.declaringType() instanceof ClassType declaringType) {
                    result = declaringType.invokeMethod(context.thread(), method, converted, invocationOptions);
                } else {
                    result = object.invokeMethod(context.thread(), method, converted,
                            invokeSuper ? invocationOptions | ObjectReference.INVOKE_NONVIRTUAL : invocationOptions);
                }
            }
        } catch (InvocationException exception) {
            throw targetException(exception);
        } finally {
            context.engine().refreshStackFrames(context.thread());
        }
        return value(result, resolveType(method.returnTypeName(), context.vm()));
    }

    private static Value invoke(
            ObjectReference receiver,
            Method method,
            List<Value> args,
            Context context,
            boolean invokeSuper
    ) throws Exception {
        List<Value> converted = DebuggerOverloadResolver.convertArguments(args, method, context);
        try {
            return receiver.invokeMethod(context.thread(), method, converted,
                    ObjectReference.INVOKE_SINGLE_THREADED
                            | (invokeSuper ? ObjectReference.INVOKE_NONVIRTUAL : 0));
        } catch (InvocationException exception) {
            throw targetException(exception);
        } finally {
            context.engine().refreshStackFrames(context.thread());
        }
    }

    private static Method findMethod(ReferenceType type, String name, String signature, boolean invokeSuper) {
        ReferenceType start = invokeSuper && type instanceof ClassType classType ? classType.superclass() : type;
        return start == null ? null : methods(start).stream()
                .filter(method -> method.name().equals(name) && method.signature().equals(signature))
                .findFirst().orElse(null);
    }

    static TargetEvaluationException targetException(InvocationException exception) {
        ObjectReference target = exception.exception();
        String detail = "";
        try {
            Field message = fields(target.referenceType()).stream()
                    .filter(field -> field.name().equals("detailMessage"))
                    .findFirst().orElse(null);
            if (message != null && target.getValue(message) instanceof StringReference reference) {
                detail = reference.value();
            }
        } catch (RuntimeException ignored) {
            // The target may be unwinding or disconnected while its exception is inspected.
        }
        String description = target.referenceType().name() + (detail.isBlank() ? "" : ": " + detail);
        return new TargetEvaluationException(description, exception);
    }

    private static EvalValue cast(EvalValue value, String target, Context context) {
        if (!isPrimitive(target)) {
            ReferenceType type = resolveType(target, context.vm());
            if (type == null) {
                throw new IllegalArgumentException("Cannot cast value to " + target);
            }
            if (value.value() == null) return new EvalValue(null, type, false);
            if (!(value.value() instanceof ObjectReference object) || !isAssignable(object.referenceType(), type)) {
                throw new IllegalArgumentException("Cannot cast value to " + target);
            }
            return new EvalValue(value.value(), type, false);
        }
        if (value.value() == null) return value;
        return new EvalValue(DebuggerOverloadResolver.mirrorPrimitive(context.vm(), value.value(),
                DebuggerPrimitiveKind.fromPrimitiveName(target)), null, false);
    }

    private static EvalValue prefix(PrefixExpression prefix, Context context) throws Exception {
        Value operand = evaluate(prefix.getOperand(), context).value();
        PrefixExpression.Operator operator = prefix.getOperator();
        if (operator == PrefixExpression.Operator.NOT) return value(context.vm().mirrorOf(!toBoolean(operand)));
        Number number = toNumber(operand);
        if (operator == PrefixExpression.Operator.PLUS) return value(mirrorNumber(context.vm(), number));
        if (operator == PrefixExpression.Operator.MINUS) {
            if (number instanceof Double) return value(context.vm().mirrorOf(-number.doubleValue()));
            if (number instanceof Float) return value(context.vm().mirrorOf(-number.floatValue()));
            if (number instanceof Long) return value(context.vm().mirrorOf(-number.longValue()));
            return value(context.vm().mirrorOf(-number.intValue()));
        }
        if (operator == PrefixExpression.Operator.COMPLEMENT) return value(context.vm().mirrorOf(~number.longValue()));
        throw new UnsupportedOperationException("Prefix operator is not implemented: " + operator);
    }

    private static EvalValue infix(InfixExpression infix, Context context) throws Exception {
        EvalValue left = evaluate(infix.getLeftOperand(), context);
        if (infix.getOperator() == InfixExpression.Operator.CONDITIONAL_AND && !toBoolean(left.value())) {
            return value(context.vm().mirrorOf(false));
        }
        if (infix.getOperator() == InfixExpression.Operator.CONDITIONAL_OR && toBoolean(left.value())) {
            return value(context.vm().mirrorOf(true));
        }
        EvalValue result = evaluateBinary(left, infix.getOperator(), evaluate(infix.getRightOperand(), context), context);
        for (Object operand : infix.extendedOperands()) {
            result = evaluateBinary(result, infix.getOperator(), evaluate((Expression) operand, context), context);
        }
        return result;
    }

    private static EvalValue evaluateBinary(EvalValue left, InfixExpression.Operator operator, EvalValue right, Context context) {
        VirtualMachine vm = context.vm();
        if (operator == InfixExpression.Operator.CONDITIONAL_AND) return value(vm.mirrorOf(toBoolean(left.value()) && toBoolean(right.value())));
        if (operator == InfixExpression.Operator.CONDITIONAL_OR) return value(vm.mirrorOf(toBoolean(left.value()) || toBoolean(right.value())));
        if (operator == InfixExpression.Operator.EQUALS) return value(vm.mirrorOf(equalsValue(left.value(), right.value())));
        if (operator == InfixExpression.Operator.NOT_EQUALS) return value(vm.mirrorOf(!equalsValue(left.value(), right.value())));
        if (operator == InfixExpression.Operator.PLUS && isString(left.value()) || isString(right.value())) {
            return value(vm.mirrorOf(stringValue(left.value()) + stringValue(right.value())));
        }
        if (operator == InfixExpression.Operator.AND || operator == InfixExpression.Operator.OR
                || operator == InfixExpression.Operator.XOR || operator == InfixExpression.Operator.LEFT_SHIFT
                || operator == InfixExpression.Operator.RIGHT_SHIFT_SIGNED
                || operator == InfixExpression.Operator.RIGHT_SHIFT_UNSIGNED) {
            long a = toNumber(left.value()).longValue();
            long b = toNumber(right.value()).longValue();
            long result = switch (operator.toString()) {
                case "&" -> a & b;
                case "|" -> a | b;
                case "^" -> a ^ b;
                case "<<" -> a << b;
                case ">>" -> a >> b;
                default -> a >>> b;
            };
            return value(vm.mirrorOf(result));
        }
        Number a = toNumber(left.value());
        Number b = toNumber(right.value());
        if (operator == InfixExpression.Operator.LESS) return value(vm.mirrorOf(compare(a, b) < 0));
        if (operator == InfixExpression.Operator.LESS_EQUALS) return value(vm.mirrorOf(compare(a, b) <= 0));
        if (operator == InfixExpression.Operator.GREATER) return value(vm.mirrorOf(compare(a, b) > 0));
        if (operator == InfixExpression.Operator.GREATER_EQUALS) return value(vm.mirrorOf(compare(a, b) >= 0));
        boolean floating = isFloating(a) || isFloating(b);
        if (floating) {
            double x = a.doubleValue(), y = b.doubleValue();
            return value(vm.mirrorOf(switch (operator.toString()) {
                case "+" -> x + y; case "-" -> x - y; case "*" -> x * y;
                case "/" -> x / y; default -> x % y;
            }));
        }
        long x = a.longValue(), y = b.longValue();
        long result = switch (operator.toString()) {
            case "+" -> x + y; case "-" -> x - y; case "*" -> x * y;
            case "/" -> x / y; default -> x % y;
        };
        return value(vm.mirrorOf(a instanceof Long || b instanceof Long ? result : (int) result));
    }

    private static boolean isString(Value value) {
        return value instanceof StringReference || value != null && value.type().name().equals("java.lang.String");
    }

    private static String stringValue(Value value) {
        if (value == null) return "null";
        if (value instanceof StringReference string) return string.value();
        return value.toString();
    }

    private static boolean equalsValue(Value left, Value right) {
        if (left == null || right == null) return left == right;
        if (left instanceof BooleanValue || right instanceof BooleanValue) return toBoolean(left) == toBoolean(right);
        if (left instanceof PrimitiveValue || right instanceof PrimitiveValue) return compare(toNumber(left), toNumber(right)) == 0;
        return left instanceof ObjectReference a && right instanceof ObjectReference b && a.uniqueID() == b.uniqueID();
    }

    private static boolean toBoolean(Value value) {
        if (value instanceof BooleanValue booleanValue) return booleanValue.booleanValue();
        throw new IllegalArgumentException("Boolean expression required");
    }

    static Number toNumber(Value value) {
        if (value instanceof ByteValue number) return number.byteValue();
        if (value instanceof ShortValue number) return number.shortValue();
        if (value instanceof IntegerValue number) return number.intValue();
        if (value instanceof com.sun.jdi.LongValue number) return number.longValue();
        if (value instanceof FloatValue number) return number.floatValue();
        if (value instanceof DoubleValue number) return number.doubleValue();
        if (value instanceof CharValue character) return (int) character.charValue();
        throw new IllegalArgumentException("Numeric expression required");
    }

    private static int compare(Number left, Number right) {
        return isFloating(left) || isFloating(right)
                ? Double.compare(left.doubleValue(), right.doubleValue())
                : Long.compare(left.longValue(), right.longValue());
    }

    private static boolean isFloating(Number number) { return number instanceof Float || number instanceof Double; }

    private static Value mirrorNumber(VirtualMachine vm, String token) {
        String normalized = token.replace("_", "");
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("0x") || lower.startsWith("0b") || lower.startsWith("0" ) && normalized.length() > 1
                && !normalized.contains(".") && !normalized.contains("e")) {
            throw new UnsupportedOperationException("Non-decimal numeric literals are not implemented");
        }
        char suffix = Character.toLowerCase(normalized.charAt(normalized.length() - 1));
        if (suffix == 'f') return vm.mirrorOf(Float.parseFloat(normalized.substring(0, normalized.length() - 1)));
        if (suffix == 'd') return vm.mirrorOf(Double.parseDouble(normalized.substring(0, normalized.length() - 1)));
        if (suffix == 'l') return vm.mirrorOf(Long.decode(normalized.substring(0, normalized.length() - 1)));
        if (normalized.contains(".") || normalized.contains("e") || normalized.contains("E")) return vm.mirrorOf(Double.parseDouble(normalized));
        return vm.mirrorOf(Integer.decode(normalized));
    }

    private static Value mirrorNumber(VirtualMachine vm, Number number) {
        if (number instanceof Double) return vm.mirrorOf(number.doubleValue());
        if (number instanceof Float) return vm.mirrorOf(number.floatValue());
        if (number instanceof Long) return vm.mirrorOf(number.longValue());
        return vm.mirrorOf(number.intValue());
    }

    private static Expression parse(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Expression must not be blank");
        ASTParser parser = ASTParser.newParser(AST.JLS21);
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(source.toCharArray());
        ASTNode node = parser.createAST(null);
        if (!(node instanceof Expression expression) || (node.getFlags() & ASTNode.MALFORMED) != 0) {
            throw new IllegalArgumentException("Invalid Java expression: " + source);
        }
        return expression;
    }

    private static EvalValue value(Value value) { return new EvalValue(value, null, false); }
    private static EvalValue value(Value value, ReferenceType declaredType) {
        return new EvalValue(value, declaredType, false);
    }
    private static EvalValue type(ReferenceType type) { return new EvalValue(null, type, true); }

    private void begin(ThreadReference thread) {
        this.lifecycle.begin(thread);
    }

    private void end(ThreadReference thread) {
        this.lifecycle.end(thread);
    }

    void refreshStackFrames(ThreadReference thread) {
        IDebugAdapterContext context = this.debugContext;
        if (context != null && thread != null) {
            try {
                context.getStackFrameManager().reloadStackFrames(thread);
            } catch (RuntimeException ignored) {
                // The debuggee may terminate while an evaluation is unwinding.
            }
        }
    }

    @FunctionalInterface
    interface VariableNameResolver {
        String displayedName(String binaryName, String runtimeName);
    }

    private record EvalValue(Value value, ReferenceType type, boolean typeLiteral) { }
    record Context(StackFrame frame, ObjectReference thisObject, VariableNameResolver variableNameResolver,
                           RichJavaExpressionEngine engine, ThreadReference thread) {
        VirtualMachine vm() { return frame != null ? frame.virtualMachine() : thisObject.virtualMachine(); }
    }
    private record CompletionOwner(ReferenceType type, boolean typeLiteral, boolean array) { }
    static final class TargetEvaluationException extends Exception {
        private TargetEvaluationException(String description, InvocationException cause) {
            super("Target method threw " + description, cause);
        }
    }
    private record ScoredMethod(Method method, int score) { }

}
