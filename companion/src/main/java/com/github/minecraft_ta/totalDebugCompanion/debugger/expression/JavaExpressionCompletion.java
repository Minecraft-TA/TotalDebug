package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionProposal;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionRange;
import com.microsoft.java.debug.core.protocol.Types;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TypeLiteral;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.fields;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.findField;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.isAssignableName;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.isPrimitive;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.methods;

/** Metadata-only completion for one Java debugger expression. Never invokes target code. */
final class JavaExpressionCompletion {
    private static final List<String> KEYWORDS = List.of("true", "false", "null", "this", "super");

    private final RichJavaExpressionEngine engine;
    private final JavaExpressionEvaluator evaluator;
    private final RichJavaExpressionEngine.VariableNameResolver variableNameResolver;
    private final RichJavaExpressionEngine.TypeScopeResolver typeScopeResolver;

    JavaExpressionCompletion(
            RichJavaExpressionEngine engine,
            JavaExpressionEvaluator evaluator,
            RichJavaExpressionEngine.VariableNameResolver variableNameResolver,
            RichJavaExpressionEngine.TypeScopeResolver typeScopeResolver
    ) {
        this.engine = engine;
        this.evaluator = evaluator;
        this.variableNameResolver = variableNameResolver;
        this.typeScopeResolver = typeScopeResolver;
    }

    List<Types.CompletionItem> codeComplete(StackFrame frame, String snippet, int line, int column) {
        int offset = DebuggerCompletionRange.offsetOf(snippet, line, column);
        DebuggerCompletionRange range = DebuggerCompletionRange.around(snippet, offset);
        List<DebuggerCompletionProposal> proposals = complete(frame, snippet, range);
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
            DebuggerCompletionRange range
    ) {
        Map<String, DebuggerCompletionProposal> result = new LinkedHashMap<>();
        try {
            if (range.memberAccess()) {
                String ownerText = range.ownerExpression(snippet).trim();
                if (!ownerText.isEmpty()) {
                    CompletionOwner owner = resolveCompletionOwner(
                            JavaExpressionEvaluator.parse(ownerText),
                            this.evaluator.context(frame)
                    );
                    addMembers(result, owner, range);
                }
            } else {
                addLocalsAndFields(result, frame, range);
                addTypes(result, frame, range);
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
        Method method = frame.location().method();
        for (LocalVariable local : frame.visibleVariables()) {
            String name = this.variableNameResolver.displayedName(
                    binaryName,
                    method.name(),
                    method.signature(),
                    local.name()
            );
            add(result, proposal(name, name, DebuggerCompletionProposal.Kind.VARIABLE,
                    local.typeName(), range, 10));
        }
        ObjectReference thisObject = frame.thisObject();
        if (thisObject != null) {
            addFields(result, thisObject.referenceType(), range, 20, false);
        }
        addFields(result, frame.location().declaringType(), range, 25, true);
        if (thisObject != null) {
            addMethods(result, thisObject.referenceType(), range, 35, false);
        }
    }

    private void addTypes(
            Map<String, DebuggerCompletionProposal> result,
            StackFrame frame,
            DebuggerCompletionRange range
    ) {
        DebuggerTypeScope scope = this.typeScopeResolver.scope(frame.location().declaringType().name());
        if (scope == null) {
            return;
        }
        for (DebuggerTypeScope.TypeCandidate type : scope.complete(
                range.prefix(), frame.location().declaringType(),
                this.engine.typeCatalog(frame.virtualMachine()))) {
            add(result, proposal(type.label(), type.insertion(), DebuggerCompletionProposal.Kind.TYPE,
                    type.detail(), range, type.rank()));
        }
    }

    /** Resolves only static/runtime metadata. This method must never invoke a target method. */
    static CompletionOwner resolveCompletionOwner(
            Expression expression,
            JavaExpressionEvaluator.Context context
    ) throws Exception {
        if (expression instanceof ParenthesizedExpression parenthesized) {
            return resolveCompletionOwner(parenthesized.getExpression(), context);
        }
        if (expression instanceof ThisExpression) {
            return new CompletionOwner(context.frame().location().declaringType(), false, false);
        }
        if (expression instanceof TypeLiteral typeLiteral) {
            return new CompletionOwner(JavaExpressionEvaluator.resolveType(
                    typeLiteral.getType().toString(), context), true, false);
        }
        if (expression instanceof CastExpression cast) {
            return declaredOwner(cast.getType().toString(), context);
        }
        if (expression instanceof ArrayAccess access) {
            CompletionOwner array = resolveCompletionOwner(access.getArray(), context);
            return array != null && array.typeName() != null && array.typeName().endsWith("[]")
                    ? declaredOwner(array.typeName().substring(0, array.typeName().length() - 2), context) : null;
        }
        if (expression instanceof SimpleName name) {
            return resolveSimpleCompletionOwner(name.getIdentifier(), context);
        }
        if (expression instanceof QualifiedName name) {
            ReferenceType type = JavaExpressionEvaluator.resolveType(name.getFullyQualifiedName(), context);
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

    private static CompletionOwner resolveSimpleCompletionOwner(
            String name,
            JavaExpressionEvaluator.Context context
    ) throws Exception {
        StackFrame frame = context.frame();
        Method method = frame.location().method();
        for (LocalVariable local : frame.visibleVariables()) {
            if (local.name().equals(name)
                    || context.variableNameResolver().displayedName(
                    frame.location().declaringType().name(),
                    method.name(),
                    method.signature(),
                    local.name()
            ).equals(name)) {
                return declaredOwner(local.typeName(), context);
            }
        }
        Field field = findField(frame.location().declaringType(), name, false);
        if (field != null) {
            return declaredOwner(field.typeName(), context);
        }
        ReferenceType type = JavaExpressionEvaluator.resolveType(name, context);
        return type == null ? null : new CompletionOwner(type, true, false);
    }

    private static CompletionOwner fieldCompletionOwner(
            CompletionOwner owner,
            String name,
            JavaExpressionEvaluator.Context context
    ) {
        if (owner != null && owner.array() && name.equals("length")) return declaredOwner("int", context);
        if (owner == null || owner.array() || owner.type() == null) {
            return null;
        }
        Field field = findField(owner.type(), name, owner.typeLiteral());
        return field == null ? null : declaredOwner(field.typeName(), context);
    }

    private static CompletionOwner methodCompletionOwner(
            CompletionOwner owner,
            String name,
            List<?> arguments,
            JavaExpressionEvaluator.Context context
    ) {
        if (owner == null || owner.array() || owner.type() == null) {
            return null;
        }
        List<Expression> expressions = arguments.stream().map(Expression.class::cast).toList();
        List<ScoredMethod> compatible = methods(owner.type()).stream()
                .filter(candidate -> candidate.name().equals(name))
                .filter(candidate -> !owner.typeLiteral() || candidate.isStatic())
                .map(candidate -> new ScoredMethod(candidate, staticCompatibility(candidate, expressions, context)))
                .filter(candidate -> candidate.score() >= 0)
                .sorted(Comparator.comparingInt(ScoredMethod::score)
                        .thenComparing(candidate -> candidate.method().isVarArgs())
                        .thenComparing(candidate -> candidate.method().signature()))
                .toList();
        if (compatible.isEmpty()) {
            return null;
        }
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
        return method == null ? null : declaredOwner(method.returnTypeName(), context);
    }

    private static int staticCompatibility(
            Method method,
            List<Expression> arguments,
            JavaExpressionEvaluator.Context context
    ) {
        try {
            List<String> parameters = method.argumentTypeNames();
            if (!method.isVarArgs() && parameters.size() != arguments.size()) {
                return -1;
            }
            if (method.isVarArgs() && arguments.size() < parameters.size() - 1) {
                return -1;
            }
            boolean directArray = method.isVarArgs() && arguments.size() == parameters.size()
                    && staticValueCompatibility(
                    staticTypeName(arguments.getLast(), context), parameters.getLast(), context) >= 0;
            int score = method.isVarArgs() && !directArray ? 100 : 0;
            for (int i = 0; i < arguments.size(); i++) {
                if (directArray && i == parameters.size() - 1) {
                    continue;
                }
                String parameter = parameters.get(Math.min(i, parameters.size() - 1));
                if (method.isVarArgs() && i >= parameters.size() - 1) {
                    parameter = parameter.substring(0, parameter.length() - 2);
                }
                int current = staticValueCompatibility(staticTypeName(arguments.get(i), context), parameter, context);
                if (current < 0) {
                    return -1;
                }
                score += current;
            }
            return score;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static int staticValueCompatibility(
            String source,
            String target,
            JavaExpressionEvaluator.Context context
    ) {
        if (source == null) {
            return 20;
        }
        if (source.equals("<null>")) {
            return isPrimitive(target) ? -1 : 20;
        }
        DebuggerPrimitiveKind sourcePrimitive = DebuggerPrimitiveKind.fromPrimitiveName(source);
        DebuggerPrimitiveKind sourceWrapper = DebuggerPrimitiveKind.fromTypeName(source);
        DebuggerPrimitiveKind targetPrimitive = DebuggerPrimitiveKind.fromPrimitiveName(target);
        if (targetPrimitive != null) {
            if (sourcePrimitive != null) {
                return sourcePrimitive.wideningCostTo(targetPrimitive);
            }
            if (sourceWrapper != null) {
                int widening = sourceWrapper.wideningCostTo(targetPrimitive);
                return widening < 0 ? -1 : 10 + widening;
            }
            return -1;
        }
        if (sourcePrimitive != null) {
            String boxed = sourcePrimitive.boxedName();
            return boxed.equals(target) ? 10 : isAssignableName(boxed, target, context.vm()) ? 12 : -1;
        }
        return source.equals(target) ? 0 : isAssignableName(source, target, context.vm()) ? 2 : -1;
    }

    static String staticTypeName(Expression expression, JavaExpressionEvaluator.Context context) {
        if (expression instanceof ParenthesizedExpression parenthesized) {
            return staticTypeName(parenthesized.getExpression(), context);
        }
        if (expression instanceof BooleanLiteral) {
            return "boolean";
        }
        if (expression instanceof CharacterLiteral) {
            return "char";
        }
        if (expression instanceof StringLiteral) {
            return "java.lang.String";
        }
        if (expression instanceof NumberLiteral literal) {
            String token = literal.getToken().replace("_", "").toLowerCase(Locale.ROOT);
            if (token.endsWith("l")) {
                return "long";
            }
            if (token.endsWith("f")) {
                return "float";
            }
            if (token.endsWith("d") || token.contains(".") || token.contains("e")) {
                return "double";
            }
            return "int";
        }
        if (expression instanceof NullLiteral) {
            return "<null>";
        }
        if (expression instanceof CastExpression cast) {
            return declaredOwner(cast.getType().toString(), context).typeName();
        }
        try {
            if (expression instanceof org.eclipse.jdt.core.dom.ConditionalExpression conditional) {
                return DebuggerConditionalType.resolve(conditional, context);
            }
            if (expression instanceof org.eclipse.jdt.core.dom.PrefixExpression prefix) {
                if (prefix.getOperator() == org.eclipse.jdt.core.dom.PrefixExpression.Operator.NOT) return "boolean";
                var kind = DebuggerPrimitiveKind.fromTypeName(staticTypeName(prefix.getOperand(), context));
                return kind == null ? null : DebuggerConditionalType.promote(kind, DebuggerPrimitiveKind.INT);
            }
            if (expression instanceof org.eclipse.jdt.core.dom.InfixExpression infix) {
                String left = staticTypeName(infix.getLeftOperand(), context);
                List<Expression> operands = new ArrayList<>();
                operands.add(infix.getRightOperand());
                infix.extendedOperands().forEach(operand -> operands.add((Expression) operand));
                for (Expression operand : operands) {
                    String right = staticTypeName(operand, context);
                    String operator = infix.getOperator().toString();
                    if (List.of("==", "!=", "<", "<=", ">", ">=", "&&", "||").contains(operator)) left = "boolean";
                    else if (operator.equals("+") && ("java.lang.String".equals(left) || "java.lang.String".equals(right))) left = "java.lang.String";
                    else {
                        var a = DebuggerPrimitiveKind.fromTypeName(left);
                        var b = DebuggerPrimitiveKind.fromTypeName(right);
                        if (a == null || b == null) return null;
                        if (a == DebuggerPrimitiveKind.BOOLEAN && b == a) left = "boolean";
                        else left = DebuggerConditionalType.promote(a,
                                List.of("<<", ">>", ">>>").contains(operator) ? DebuggerPrimitiveKind.INT : b);
                    }
                }
                return left;
            }
            CompletionOwner resolved = resolveCompletionOwner(expression, context);
            return resolved == null ? null : resolved.typeName();
        } catch (Exception ignored) {
            return null;
        }
    }

    static ReferenceType lexicalSuperclass(JavaExpressionEvaluator.Context context) {
        ReferenceType declaringType = context.frame().location().declaringType();
        return declaringType instanceof ClassType classType ? classType.superclass() : null;
    }

    private void addMembers(
            Map<String, DebuggerCompletionProposal> result,
            CompletionOwner owner,
            DebuggerCompletionRange range
    ) {
        if (owner.array()) {
            add(result, proposal("length", "length", DebuggerCompletionProposal.Kind.FIELD, "int", range, 20));
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
                    field.isStatic() ? DebuggerCompletionProposal.Kind.CONSTANT
                            : DebuggerCompletionProposal.Kind.FIELD,
                    field.typeName(), range, rank));
        }
    }

    private void addMethods(
            Map<String, DebuggerCompletionProposal> result,
            ReferenceType type,
            DebuggerCompletionRange range,
            int rank,
            boolean staticOnly
    ) {
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

    private static void add(
            Map<String, DebuggerCompletionProposal> result,
            DebuggerCompletionProposal proposal
    ) {
        String key = proposal.kind() + "\0" + proposal.label() + "\0" + proposal.insertionText();
        result.merge(key, proposal, (oldValue, newValue) ->
                newValue.rank() < oldValue.rank() ? newValue : oldValue);
    }

    private static String methodLabel(Method method) {
        return "(" + String.join(", ", method.argumentTypeNames()) + ")";
    }

    private static CompletionOwner declaredOwner(String name, JavaExpressionEvaluator.Context context) {
        ReferenceType type = JavaExpressionEvaluator.resolveType(name, context);
        return new CompletionOwner(type, false, name.endsWith("[]"), type == null ? name : type.name());
    }

    record CompletionOwner(ReferenceType type, boolean typeLiteral, boolean array, String typeName) {
        CompletionOwner(ReferenceType type, boolean typeLiteral, boolean array) {
            this(type, typeLiteral, array, type == null ? null : type.name());
        }
    }

    private record ScoredMethod(Method method, int score) {
    }
}
