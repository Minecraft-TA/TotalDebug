package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.microsoft.java.debug.core.IEvaluatableBreakpoint;
import com.microsoft.java.debug.core.adapter.ICompletionsProvider;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.microsoft.java.debug.core.protocol.Types;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

/** One JDI-backed expression engine used for evaluation and runtime completion. */
final class RichJavaExpressionEngine implements IEvaluationProvider, ICompletionsProvider {
    private static final List<String> KEYWORDS = List.of("true", "false", "null", "this", "super");
    private static final Map<String, String> BOXED_TYPES = Map.of(
            "boolean", "java.lang.Boolean", "byte", "java.lang.Byte", "short", "java.lang.Short",
            "char", "java.lang.Character", "int", "java.lang.Integer", "long", "java.lang.Long",
            "float", "java.lang.Float", "double", "java.lang.Double"
    );
    private static final Map<String, String> UNBOX_METHODS = Map.of(
            "java.lang.Boolean", "booleanValue", "java.lang.Byte", "byteValue",
            "java.lang.Short", "shortValue", "java.lang.Character", "charValue",
            "java.lang.Integer", "intValue", "java.lang.Long", "longValue",
            "java.lang.Float", "floatValue", "java.lang.Double", "doubleValue"
    );

    private final VariableNameResolver variableNameResolver;
    private final Map<Long, AtomicInteger> evaluations = new ConcurrentHashMap<>();
    private volatile IDebugAdapterContext debugContext;

    RichJavaExpressionEngine(VariableNameResolver variableNameResolver) {
        this.variableNameResolver = Objects.requireNonNull(variableNameResolver, "variableNameResolver");
    }

    @Override
    public void initialize(IDebugAdapterContext debugContext, Map<String, Object> options) {
        this.debugContext = debugContext;
    }

    @Override
    public boolean isInEvaluation(ThreadReference thread) {
        return thread != null && this.evaluations.containsKey(thread.uniqueID());
    }

    @Override
    public CompletableFuture<Value> evaluate(String expression, ThreadReference thread, int depth) {
        return evaluateInternal(expression, thread, depth, false);
    }

    private CompletableFuture<Value> evaluateInternal(
            String expression,
            ThreadReference thread,
            int depth,
            boolean breakpoint
    ) {
        return CompletableFuture.supplyAsync(() -> {
            begin(thread);
            try {
                StackFrame frame = thread.frame(depth);
                return evaluate(parse(expression), new Context(frame, frame.thisObject(), this.variableNameResolver,
                        this, thread, breakpoint)).value();
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
                        this, thread, false)).value();
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
        return evaluateInternal(breakpoint.getCondition(), thread, 0, true);
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
                return invoke(thisContext, method, List.of(args == null ? new Value[0] : args), thread, invokeSuper, this);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            } finally {
                end(thread);
            }
        });
    }

    @Override
    public void clearState(ThreadReference thread) {
        if (thread != null) {
            this.evaluations.remove(thread.uniqueID());
        }
    }

    @Override
    public List<Types.CompletionItem> codeComplete(StackFrame frame, String snippet, int line, int column) {
        int offset = offsetOf(snippet, line, column);
        CompletionRange range = completionRange(snippet, offset);
        List<DebuggerCompletionProposal> proposals = complete(frame, snippet, range, offset);
        List<Types.CompletionItem> result = new ArrayList<>(proposals.size());
        for (DebuggerCompletionProposal proposal : proposals) {
            Types.CompletionItem item = new Types.CompletionItem(proposal.label(), proposal.insertionText());
            item.type = proposal.kind().name().toLowerCase(Locale.ROOT);
            item.sortText = String.format(Locale.ROOT, "%04d-%s", proposal.rank(), proposal.label());
            item.start = proposal.replacementStart();
            item.number = proposal.replacementEnd() - proposal.replacementStart();
            result.add(item);
        }
        return result;
    }

    List<DebuggerCompletionProposal> complete(StackFrame frame, String snippet, int line, int column) {
        int offset = offsetOf(snippet, line, column);
        CompletionRange range = completionRange(snippet, offset);
        return complete(frame, snippet, range, offset);
    }

    private List<DebuggerCompletionProposal> complete(
            StackFrame frame,
            String snippet,
            CompletionRange range,
            int offset
    ) {
        Map<String, DebuggerCompletionProposal> result = new LinkedHashMap<>();
        try {
            if (range.memberAccess()) {
                String ownerText = snippet.substring(0, range.ownerEnd()).trim();
                if (!ownerText.isEmpty()) {
                    EvalValue owner = evaluate(parse(ownerText), new Context(frame, frame.thisObject(),
                            this.variableNameResolver, this, frame.thread(), false));
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
            CompletionRange range
    ) throws Exception {
        String binaryName = frame.location().declaringType().name();
        for (LocalVariable local : frame.visibleVariables()) {
            String name = this.variableNameResolver.displayedName(binaryName, local.name());
            add(result, proposal(name, name, DebuggerCompletionProposal.Kind.VARIABLE,
                    local.typeName(), range, 10));
        }
        addFields(result, frame.thisObject(), range, 20);
        addStaticMembers(result, frame.location().declaringType(), range, 25);
        if (frame.thisObject() != null) {
            addMethods(result, frame.thisObject().referenceType(), range, 35);
        }
    }

    private void addMembers(Map<String, DebuggerCompletionProposal> result, EvalValue owner, CompletionRange range) {
        if (owner.value() instanceof ArrayReference array) {
            add(result, proposal("length", "length", DebuggerCompletionProposal.Kind.FIELD,
                    "int", range, 20));
            return;
        }
        if (owner.type() != null) {
            addStaticFields(result, owner.type(), range, 20);
            addMethods(result, owner.type(), range, 30);
        } else if (owner.value() instanceof ObjectReference object) {
            addFields(result, object, range, 20);
            addMethods(result, object.referenceType(), range, 30);
        }
    }

    private void addFields(Map<String, DebuggerCompletionProposal> result, ObjectReference object, CompletionRange range, int rank) {
        if (object != null) {
            addFields(result, object, object.referenceType(), range, rank);
        }
    }

    private void addFields(
            Map<String, DebuggerCompletionProposal> result,
            Value objectValue,
            ReferenceType type,
            CompletionRange range,
            int rank
    ) {
        for (Field field : fields(type)) {
            if (field.isStatic() && objectValue instanceof ObjectReference) {
                // Static fields are still useful through an instance in a debugger.
            }
            add(result, proposal(field.name(), field.name(),
                    field.isStatic() ? DebuggerCompletionProposal.Kind.CONSTANT : DebuggerCompletionProposal.Kind.FIELD,
                    field.typeName(), range, rank));
        }
    }

    private void addStaticFields(Map<String, DebuggerCompletionProposal> result, ReferenceType type,
                                 CompletionRange range, int rank) {
        for (Field field : fields(type)) {
            if (field.isStatic()) {
                add(result, proposal(field.name(), field.name(), DebuggerCompletionProposal.Kind.CONSTANT,
                        field.typeName(), range, rank));
            }
        }
    }

    private void addStaticMembers(Map<String, DebuggerCompletionProposal> result, ReferenceType type, CompletionRange range, int rank) {
        for (Field field : fields(type)) {
            if (field.isStatic()) {
                add(result, proposal(field.name(), field.name(), DebuggerCompletionProposal.Kind.CONSTANT,
                        field.typeName(), range, rank));
            }
        }
    }

    private void addMethods(Map<String, DebuggerCompletionProposal> result, ReferenceType type, CompletionRange range, int rank) {
        for (Method method : methods(type)) {
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
            CompletionRange range,
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
            return value(array.getValue(index));
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
            return methodInvocation(invocation, context, false);
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
                return value(context.frame().getValue(local));
            }
        }
        if (context.thisObject() != null) {
            Field field = findField(context.thisObject().referenceType(), name, false);
            if (field != null) {
                return value(context.thisObject().getValue(field));
            }
        }
        if (context.frame() != null) {
            Field field = findField(context.frame().location().declaringType(), name, true);
            if (field != null) {
                return value(context.frame().location().declaringType().getValue(field));
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
        return field(evaluate(name.getQualifier(), context), name.getName().getIdentifier(), context);
    }

    private static EvalValue field(EvalValue owner, String name, Context context) {
        if (owner.type() != null) {
            Field field = findField(owner.type(), name, true);
            if (field == null) {
                throw new IllegalArgumentException("Unknown static field " + owner.type().name() + "." + name);
            }
            return value(owner.type().getValue(field));
        }
        if (owner.value() == null) {
            throw new IllegalArgumentException("Cannot read field '" + name + "' from null");
        }
        if (owner.value() instanceof ArrayReference array && name.equals("length")) {
            return value(array.virtualMachine().mirrorOf(array.length()));
        }
        if (!(owner.value() instanceof ObjectReference object)) {
            throw new IllegalArgumentException("Field access requires an object value");
        }
        Field field = findField(object.referenceType(), name, false);
        if (field == null) {
            throw new IllegalArgumentException("Unknown field " + object.referenceType().name() + "." + name);
        }
        return value(object.getValue(field));
    }

    private static EvalValue methodInvocation(MethodInvocation invocation, Context context, boolean invokeSuper) throws Exception {
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
        return invoke(receiver, invocation.getName().getIdentifier(), args, context, false);
    }

    private static EvalValue superMethodInvocation(SuperMethodInvocation invocation, Context context) throws Exception {
        List<Value> args = new ArrayList<>();
        for (Object argument : invocation.arguments()) {
            args.add(evaluate((Expression) argument, context).value());
        }
        return invoke(value(context.thisObject()), invocation.getName().getIdentifier(), args, context, true);
    }

    private static EvalValue invoke(
            EvalValue receiver,
            String name,
            List<Value> args,
            Context context,
            boolean invokeSuper
    ) throws Exception {
        if (receiver.value() == null && receiver.type() == null) {
            throw new IllegalArgumentException("Cannot invoke " + name + " on null");
        }
        ReferenceType type = receiver.type() != null
                ? receiver.type()
                : ((ObjectReference) receiver.value()).referenceType();
        Method method = selectMethod(type, name, args, invokeSuper);
        if (method == null) {
            throw new IllegalArgumentException("No compatible overload for " + type.name() + "." + name);
        }
        List<Value> converted = convertArguments(args, method, context, method.isVarArgs());
        Value result;
        try {
            // The adapter owns the event-set suspension. INVOKE_SINGLE_THREADED leaves that
            // suspension untouched; using resume-all here adds a suspend count that Continue
            // cannot balance after a manual or conditional evaluation.
            int invocationOptions = ObjectReference.INVOKE_SINGLE_THREADED;
            if (receiver.type() != null) {
            if (!(receiver.type() instanceof ClassType classType)) {
                throw new UnsupportedOperationException("Static interface invocation is not supported by this VM");
            }
            result = classType.invokeMethod(context.thread(), method, converted, invocationOptions);
            } else {
                ObjectReference object = (ObjectReference) receiver.value();
                if (method.isStatic() && method.declaringType() instanceof ClassType declaringType) {
                    result = declaringType.invokeMethod(context.thread(), method, converted, invocationOptions);
                } else {
                    result = object.invokeMethod(context.thread(), method, converted,
                            invokeSuper ? ObjectReference.INVOKE_NONVIRTUAL : invocationOptions);
                }
            }
        } finally {
            context.engine().refreshStackFrames(context.thread());
        }
        return value(result);
    }

    private static Value invoke(
            ObjectReference receiver,
            Method method,
            List<Value> args,
            ThreadReference thread,
            boolean invokeSuper,
            RichJavaExpressionEngine engine
    ) throws Exception {
        List<Value> converted = args;
        if (method.isVarArgs() && args.size() != method.argumentTypeNames().size()) {
            converted = packVarargs(args, method, receiver.virtualMachine());
        }
        try {
            return receiver.invokeMethod(thread, method, converted,
                    invokeSuper ? ObjectReference.INVOKE_NONVIRTUAL : 0);
        } finally {
            engine.refreshStackFrames(thread);
        }
    }

    private static Method selectMethod(ReferenceType type, String name, List<Value> args, boolean invokeSuper) {
        ReferenceType start = invokeSuper && type instanceof ClassType classType ? classType.superclass() : type;
        if (start == null) {
            return null;
        }
        return methods(start).stream()
                .filter(method -> method.name().equals(name))
                .map(method -> new ScoredMethod(method, compatibility(method, args)))
                .filter(scored -> scored.score() >= 0)
                .sorted(Comparator.comparingInt(ScoredMethod::score)
                        .thenComparing(scored -> scored.method().isBridge()))
                .map(ScoredMethod::method)
                .findFirst()
                .orElse(null);
    }

    private static Method findMethod(ReferenceType type, String name, String signature, boolean invokeSuper) {
        ReferenceType start = invokeSuper && type instanceof ClassType classType ? classType.superclass() : type;
        return start == null ? null : methods(start).stream()
                .filter(method -> method.name().equals(name) && method.signature().equals(signature))
                .findFirst().orElse(null);
    }

    private static int compatibility(Method method, List<Value> args) {
        List<String> parameters = method.argumentTypeNames();
        if (!method.isVarArgs() && parameters.size() != args.size()) {
            return -1;
        }
        if (method.isVarArgs() && args.size() < parameters.size() - 1) {
            return -1;
        }
        int score = method.isVarArgs() ? 10 : 0;
        for (int i = 0; i < args.size(); i++) {
            String parameter = parameters.get(Math.min(i, parameters.size() - 1));
            if (method.isVarArgs() && i >= parameters.size() - 1) {
                parameter = parameter.substring(0, parameter.length() - 2);
            }
            int current = valueCompatibility(args.get(i), parameter);
            if (current < 0) {
                return -1;
            }
            score += current;
        }
        return score;
    }

    private static int valueCompatibility(Value value, String target) {
        if (value == null) {
            return isPrimitive(target) ? -1 : 20;
        }
        String source = value.type().name();
        if (isPrimitive(target)) {
            if (value instanceof PrimitiveValue) {
                return primitiveWidening(source, target);
            }
            return UNBOX_METHODS.containsKey(source) ? 20 : -1;
        }
        if (value instanceof PrimitiveValue) {
            return BOXED_TYPES.containsValue(target) ? 20 : -1;
        }
        return source.equals(target) ? 0 : isAssignableName(source, target, value.virtualMachine()) ? 2 : -1;
    }

    private static int primitiveWidening(String source, String target) {
        if (source.equals(target)) return 0;
        List<String> order = List.of("byte", "short", "int", "long", "float", "double");
        if (source.equals("char")) source = "int";
        int from = order.indexOf(source);
        int to = order.indexOf(target);
        return from >= 0 && to >= from ? to - from + 1 : -1;
    }

    private static List<Value> convertArguments(List<Value> args, Method method, Context context, boolean varargs) throws Exception {
        List<Value> converted = new ArrayList<>();
        List<String> parameters = method.argumentTypeNames();
        for (int i = 0; i < args.size(); i++) {
            String parameter = parameters.get(Math.min(i, parameters.size() - 1));
            if (varargs && i >= parameters.size() - 1) {
                // A varargs array is packed after the fixed arguments below.
                break;
            }
            converted.add(convertValue(args.get(i), parameter, context));
        }
        if (varargs) {
            if (args.size() == parameters.size()
                    && args.getLast() != null
                    && args.getLast().type().name().equals(parameters.getLast())) {
                converted.add(args.getLast());
            } else {
                converted.add(makeVarargs(args.subList(parameters.size() - 1, args.size()),
                        parameters.getLast(), context));
            }
        } else {
            while (converted.size() < args.size()) {
                converted.add(convertValue(args.get(converted.size()), parameters.get(converted.size()), context));
            }
        }
        return converted;
    }

    private static List<Value> packVarargs(List<Value> args, Method method, VirtualMachine vm) throws Exception {
        int fixed = method.argumentTypeNames().size() - 1;
        List<Value> result = new ArrayList<>(args.subList(0, Math.min(fixed, args.size())));
        String arrayName = method.argumentTypeNames().getLast();
        ArrayType arrayType = (ArrayType) resolveType(arrayName, vm);
        ArrayReference array = arrayType.newInstance(Math.max(0, args.size() - fixed));
        array.setValues(args.subList(fixed, args.size()));
        result.add(array);
        return result;
    }

    private static Value makeVarargs(List<Value> args, String arrayTypeName, Context context) throws Exception {
        ArrayType arrayType = (ArrayType) resolveType(arrayTypeName, context.vm());
        if (arrayType == null) {
            throw new IllegalArgumentException("Unknown varargs type " + arrayTypeName);
        }
        String component = arrayTypeName.substring(0, arrayTypeName.length() - 2);
        ArrayReference result = arrayType.newInstance(args.size());
        List<Value> values = new ArrayList<>();
        for (Value arg : args) {
            values.add(convertValue(arg, component, context));
        }
        result.setValues(values);
        return result;
    }

    private static Value convertValue(Value value, String target, Context context) throws Exception {
        if (value == null) return null;
        if (isPrimitive(target)) {
            if (value instanceof PrimitiveValue) return mirrorPrimitive(context.vm(), value, target);
            String unbox = UNBOX_METHODS.get(value.type().name());
            if (unbox != null) {
                return ((ObjectReference) value).invokeMethod(context.thread(),
                        methods(((ObjectReference) value).referenceType()).stream().filter(method -> method.name().equals(unbox)
                                && method.argumentTypeNames().isEmpty()).findFirst().orElseThrow(), List.of(),
                        ObjectReference.INVOKE_SINGLE_THREADED);
            }
        }
        if (value instanceof PrimitiveValue && BOXED_TYPES.containsValue(target)) {
            ClassType boxed = (ClassType) resolveType(target, context.vm());
            Method valueOf = boxed.methodsByName("valueOf").stream()
                    .filter(method -> method.argumentTypeNames().size() == 1
                            && method.argumentTypeNames().getFirst().equals(value.type().name()))
                    .findFirst().orElse(null);
            if (valueOf != null) {
                return boxed.invokeMethod(context.thread(), valueOf, List.of(value), ObjectReference.INVOKE_SINGLE_THREADED);
            }
        }
        return value;
    }

    private static Value mirrorPrimitive(VirtualMachine vm, Value value, String target) {
        Number number = toNumber(value);
        return switch (target) {
            case "byte" -> vm.mirrorOf(number.byteValue());
            case "short" -> vm.mirrorOf(number.shortValue());
            case "int" -> vm.mirrorOf(number.intValue());
            case "long" -> vm.mirrorOf(number.longValue());
            case "float" -> vm.mirrorOf(number.floatValue());
            case "double" -> vm.mirrorOf(number.doubleValue());
            default -> value;
        };
    }

    private static EvalValue cast(EvalValue value, String target, Context context) {
        if (value.value() == null || !isPrimitive(target)) return value;
        return new EvalValue(mirrorPrimitive(context.vm(), value.value(), target), null);
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

    private static Number toNumber(Value value) {
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

    private static List<Field> fields(ReferenceType type) {
        List<Field> result = new ArrayList<>();
        Set<String> visitedTypes = new HashSet<>();
        collectFields(type, result, visitedTypes);
        return result;
    }

    private static void collectFields(ReferenceType type, List<Field> result, Set<String> visitedTypes) {
        if (type == null || !visitedTypes.add(type.name())) return;
        result.addAll(type.fields());
        if (type instanceof ClassType classType) {
            collectFields(classType.superclass(), result, visitedTypes);
            for (ReferenceType iface : classType.interfaces()) collectFields(iface, result, visitedTypes);
        } else if (type instanceof com.sun.jdi.InterfaceType interfaceType) {
            for (ReferenceType iface : interfaceType.superinterfaces()) collectFields(iface, result, visitedTypes);
        }
    }

    private static List<Method> methods(ReferenceType type) {
        List<Method> result = new ArrayList<>();
        Set<String> visitedTypes = new HashSet<>();
        collectMethods(type, result, visitedTypes);
        return result;
    }

    private static void collectMethods(ReferenceType type, List<Method> result, Set<String> visitedTypes) {
        if (type == null || !visitedTypes.add(type.name())) return;
        result.addAll(type.methods());
        if (type instanceof ClassType classType) {
            collectMethods(classType.superclass(), result, visitedTypes);
            for (ReferenceType iface : classType.interfaces()) collectMethods(iface, result, visitedTypes);
        } else if (type instanceof com.sun.jdi.InterfaceType interfaceType) {
            for (ReferenceType iface : interfaceType.superinterfaces()) collectMethods(iface, result, visitedTypes);
        }
    }

    private static Field findField(ReferenceType type, String name, boolean staticOnly) {
        return fields(type).stream().filter(field -> field.name().equals(name) && (!staticOnly || field.isStatic())).findFirst().orElse(null);
    }

    private static ReferenceType resolveType(String name, VirtualMachine vm) {
        String normalized = name.replace("...", "[]");
        if (isPrimitive(normalized)) return null;
        List<ReferenceType> exact = vm.classesByName(normalized);
        if (!exact.isEmpty()) return exact.getFirst();
        if (!normalized.contains(".")) {
            for (ReferenceType type : vm.allClasses()) {
                String typeName = type.name();
                if (typeName.substring(typeName.lastIndexOf('.') + 1).equals(normalized)) return type;
            }
        }
        return null;
    }

    private static boolean isAssignableName(String source, String target, VirtualMachine vm) {
        ReferenceType sourceType = resolveType(source, vm);
        ReferenceType targetType = resolveType(target, vm);
        return sourceType != null && targetType != null && isAssignable(sourceType, targetType);
    }

    private static boolean isAssignable(ReferenceType source, ReferenceType target) {
        if (source.name().equals(target.name())) return true;
        if (source instanceof ClassType classType) {
            if (isAssignable(classType.superclass(), target)) return true;
            return classType.interfaces().stream().anyMatch(iface -> isAssignable(iface, target));
        }
        if (source instanceof com.sun.jdi.InterfaceType iface) {
            return iface.superinterfaces().stream().anyMatch(parent -> isAssignable(parent, target));
        }
        return false;
    }

    private static boolean isPrimitive(String name) {
        return Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double", "void").contains(name);
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

    private static int offsetOf(String text, int line, int column) {
        if (line < 0 || column < 0) throw new IllegalArgumentException("Completion position must not be negative");
        int offset = 0;
        for (int current = 0; current < line; current++) {
            int newline = text.indexOf('\n', offset);
            if (newline < 0) return text.length();
            offset = newline + 1;
        }
        return Math.min(text.length(), offset + column);
    }

    static CompletionRange completionRange(String text, int caret) {
        int start = caret;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        int end = caret;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        int dot = start - 1;
        if (dot < 0 || text.charAt(dot) != '.') return new CompletionRange(start, end, text.substring(start, caret), false, -1);
        int ownerEnd = dot;
        while (ownerEnd > 0 && Character.isWhitespace(text.charAt(ownerEnd - 1))) ownerEnd--;
        return new CompletionRange(start, end, text.substring(start, caret), true, ownerEnd);
    }

    private static EvalValue value(Value value) { return new EvalValue(value, null); }
    private static EvalValue type(ReferenceType type) { return new EvalValue(null, type); }

    private void begin(ThreadReference thread) {
        if (thread != null) this.evaluations.computeIfAbsent(thread.uniqueID(), ignored -> new AtomicInteger()).incrementAndGet();
    }

    private void end(ThreadReference thread) {
        if (thread == null) return;
        this.evaluations.computeIfPresent(thread.uniqueID(), (ignored, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    private void refreshStackFrames(ThreadReference thread) {
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

    record CompletionRange(int start, int end, String prefix, boolean memberAccess, int ownerEnd) { }
    private record EvalValue(Value value, ReferenceType type) { }
    private record Context(StackFrame frame, ObjectReference thisObject, VariableNameResolver variableNameResolver,
                           RichJavaExpressionEngine engine, ThreadReference thread, boolean breakpoint) {
        private VirtualMachine vm() { return frame != null ? frame.virtualMachine() : thisObject.virtualMachine(); }
    }
    private record ScoredMethod(Method method, int score) { }

}
