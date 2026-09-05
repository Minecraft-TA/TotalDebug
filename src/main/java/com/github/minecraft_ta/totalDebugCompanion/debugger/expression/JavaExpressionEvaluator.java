package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
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
import com.sun.jdi.InterfaceType;
import com.sun.jdi.InvocationException;
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
import java.util.List;
import java.util.Locale;

import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.fields;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.findField;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.isAssignable;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.isPrimitive;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.methods;

/** Evaluates one parsed Java expression against a suspended JDI frame. */
final class JavaExpressionEvaluator {
    private final RichJavaExpressionEngine.VariableNameResolver variableNameResolver;
    private final RichJavaExpressionEngine.TypeScopeResolver typeScopeResolver;
    private final FrameRefresher frameRefresher;

    JavaExpressionEvaluator(
            RichJavaExpressionEngine.VariableNameResolver variableNameResolver,
            RichJavaExpressionEngine.TypeScopeResolver typeScopeResolver,
            FrameRefresher frameRefresher
    ) {
        this.variableNameResolver = variableNameResolver;
        this.typeScopeResolver = typeScopeResolver;
        this.frameRefresher = frameRefresher;
    }

    Context context(StackFrame frame) {
        return new Context(frame, frame.thisObject(), this, frame.thread());
    }

    void refreshStackFrames(ThreadReference thread) {
        this.frameRefresher.refresh(thread);
    }

    Value evaluate(String source, StackFrame frame, ObjectReference thisObject, ThreadReference thread)
            throws Exception {
        Expression expression = parse(source);
        JavaExpressionSupport.requireSupported(expression);
        return evaluate(expression, new Context(frame, thisObject, this, thread)).value();
    }

    Value invokeMethod(
            ObjectReference receiver,
            String methodName,
            String methodSignature,
            Value[] arguments,
            ThreadReference thread,
            boolean invokeSuper
    ) throws Exception {
        if (receiver == null) {
            throw new IllegalArgumentException("An object is required to invoke " + methodName);
        }
        Method method = findMethod(receiver.referenceType(), methodName, methodSignature, invokeSuper);
        if (method == null) {
            throw new IllegalArgumentException("Unknown method " + methodName + methodSignature);
        }
        Context context = new Context(thread.frame(0), receiver, this, thread);
        return invoke(receiver, method, List.of(arguments == null ? new Value[0] : arguments), context, invokeSuper);
    }

    private static EvalValue evaluate(Expression expression, Context context) throws Exception {
        DebuggerEvaluationRunner.checkpoint();
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
            return value(context.thisObject(), context.lexicalType());
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
            return value(context.thisObject().getValue(superField), resolveType(superField.typeName(), context));
        }
        if (expression instanceof ArrayAccess access) {
            EvalValue arrayValue = evaluate(access.getArray(), context);
            if (!(arrayValue.value() instanceof ArrayReference array)) {
                throw new IllegalArgumentException("Array access requires an array value");
            }
            int index = toNumber(unbox(evaluate(access.getIndex(), context), context).value()).intValue();
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
            String targetType = DebuggerConditionalType.resolve(conditional, context);
            EvalValue selected = toBoolean(unbox(evaluate(conditional.getExpression(), context), context).value())
                    ? evaluate(conditional.getThenExpression(), context)
                    : evaluate(conditional.getElseExpression(), context);
            if (targetType.equals("<null>")) return selected;
            return value(DebuggerOverloadResolver.convertValue(selected.value(), targetType, context),
                    resolveType(targetType, context));
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
            ReferenceType target = resolveType(instanceofExpression.getRightOperand().toString(), context);
            return value(context.vm().mirrorOf(candidate.value() instanceof ObjectReference object
                    && target != null && isAssignable(object.referenceType(), target)));
        }
        if (expression instanceof TypeLiteral typeLiteral) {
            ReferenceType type = resolveType(typeLiteral.getType().toString(), context);
            if (type == null) {
                throw new IllegalArgumentException("Unknown type " + typeLiteral.getType());
            }
            return value(type.classObject());
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
                Method method = context.frame().location().method();
                for (LocalVariable candidate : context.frame().visibleVariables()) {
                    if (context.evaluator().variableNameResolver.displayedName(
                            binaryName, method.name(), method.signature(), candidate.name()).equals(name)) {
                        if (local != null) {
                            throw new IllegalArgumentException(
                                    "Displayed variable name is ambiguous in the selected frame: " + name
                            );
                        }
                        local = candidate;
                    }
                }
            }
            if (local != null) {
                return value(context.frame().getValue(local), local.type() instanceof ReferenceType declared ? declared : null);
            }
        }
        if (context.thisObject() != null) {
            Field field = findField(context.lexicalType(), name, false);
            if (field != null) {
                return value(context.thisObject().getValue(field), resolveType(field.typeName(), context));
            }
        }
        if (context.frame() != null) {
            Field field = findField(context.frame().location().declaringType(), name, true);
            if (field != null) {
                return value(context.frame().location().declaringType().getValue(field),
                        resolveType(field.typeName(), context));
            }
        }
        ReferenceType type = resolveType(name, context);
        if (type != null) {
            return type(type);
        }
        throw new IllegalArgumentException("Unknown variable or field: " + name);
    }

    private static EvalValue qualifiedName(QualifiedName name, Context context) throws Exception {
        ReferenceType fullType = resolveType(name.getFullyQualifiedName(), context);
        if (fullType != null) {
            return type(fullType);
        }
        EvalValue qualifier = evaluate(name.getQualifier(), context);
        if (qualifier.typeLiteral() && qualifier.type() != null) {
            ReferenceType nested = resolveType(qualifier.type().name() + "$" + name.getName(), context);
            if (nested != null) {
                return type(nested);
            }
        }
        return field(qualifier, name.getName().getIdentifier(), context);
    }

    private static EvalValue field(EvalValue owner, String name, Context context) {
        if (owner.typeLiteral()) {
            Field field = findField(owner.type(), name, true);
            if (field == null) {
                throw new IllegalArgumentException("Unknown static field " + owner.type().name() + "." + name);
            }
            return value(owner.type().getValue(field), resolveType(field.typeName(), context));
        }
        if (owner.value() == null) {
            throw new IllegalArgumentException("Cannot read field '" + name + "' from null");
        }
        if (owner.value() instanceof ArrayReference array && name.equals("length")) {
            return value(array.virtualMachine().mirrorOf(array.length()), resolveType("int", context));
        }
        if (!(owner.value() instanceof ObjectReference object)) {
            throw new IllegalArgumentException("Field access requires an object value");
        }
        Field field = findField(owner.type() != null ? owner.type() : object.referenceType(), name, false);
        if (field == null) {
            throw new IllegalArgumentException("Unknown field " + object.referenceType().name() + "." + name);
        }
        return value(object.getValue(field), resolveType(field.typeName(), context));
    }

    private static EvalValue methodInvocation(MethodInvocation invocation, Context context) throws Exception {
        EvalValue receiver;
        if (invocation.getExpression() == null) {
            receiver = context.thisObject() == null
                    ? type(context.frame().location().declaringType())
                    : value(context.thisObject(), context.lexicalType());
        } else {
            receiver = evaluate(invocation.getExpression(), context);
        }
        List<EvalValue> arguments = new ArrayList<>();
        for (Object argument : invocation.arguments()) {
            arguments.add(evaluate((Expression) argument, context));
        }
        return invoke(receiver, invocation.getName().getIdentifier(), arguments, context, false, null);
    }

    private static EvalValue superMethodInvocation(SuperMethodInvocation invocation, Context context) throws Exception {
        List<EvalValue> arguments = new ArrayList<>();
        for (Object argument : invocation.arguments()) {
            arguments.add(evaluate((Expression) argument, context));
        }
        return invoke(value(context.thisObject()), invocation.getName().getIdentifier(), arguments, context, true,
                JavaExpressionCompletion.lexicalSuperclass(context));
    }

    private static EvalValue invoke(
            EvalValue receiver,
            String name,
            List<EvalValue> arguments,
            Context context,
            boolean invokeSuper,
            ReferenceType lookupType
    ) throws Exception {
        if (!receiver.typeLiteral() && receiver.value() == null) {
            throw new IllegalArgumentException("Cannot invoke " + name + " on null");
        }
        ReferenceType type = lookupType != null
                ? lookupType
                : receiver.type() != null
                ? receiver.type()
                : ((ObjectReference) receiver.value()).referenceType();
        Method method = DebuggerOverloadResolver.selectMethod(type, name, arguments, receiver.typeLiteral());
        if (method == null) {
            throw new IllegalArgumentException("No compatible overload for " + type.name() + "." + name);
        }
        List<Value> converted = DebuggerOverloadResolver.convertArguments(arguments, method, context);
        Value result;
        DebuggerEvaluationRunner.checkpoint();
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
            context.evaluator().frameRefresher.refresh(context.thread());
        }
        return value(result, resolveType(method.returnTypeName(), context));
    }

    private static Value invoke(
            ObjectReference receiver,
            Method method,
            List<Value> arguments,
            Context context,
            boolean invokeSuper
    ) throws Exception {
        List<Value> converted = DebuggerOverloadResolver.convertArguments(arguments.stream().map(JavaExpressionEvaluator::value).toList(), method, context);
        DebuggerEvaluationRunner.checkpoint();
        try {
            return receiver.invokeMethod(context.thread(), method, converted,
                    ObjectReference.INVOKE_SINGLE_THREADED
                            | (invokeSuper ? ObjectReference.INVOKE_NONVIRTUAL : 0));
        } catch (InvocationException exception) {
            throw targetException(exception);
        } finally {
            context.evaluator().frameRefresher.refresh(context.thread());
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
            // The target may disconnect while its exception is inspected.
        }
        String description = target.referenceType().name() + (detail.isBlank() ? "" : ": " + detail);
        return new TargetEvaluationException(description, exception);
    }

    private static EvalValue cast(EvalValue value, String target, Context context) throws Exception {
        if (!isPrimitive(target)) {
            ReferenceType type = resolveType(target, context);
            if (type == null) {
                throw new IllegalArgumentException("Cannot cast value to " + target);
            }
            if (value.value() == null) {
                return new EvalValue(null, type, false);
            }
            if (!(value.value() instanceof ObjectReference object) || !isAssignable(object.referenceType(), type)) {
                throw new IllegalArgumentException("Cannot cast value to " + target);
            }
            return new EvalValue(value.value(), type, false);
        }
        return new EvalValue(DebuggerOverloadResolver.mirrorPrimitive(
                context.vm(), unbox(value, context).value(), DebuggerPrimitiveKind.fromPrimitiveName(target)), null, false);
    }

    private static EvalValue prefix(PrefixExpression prefix, Context context) throws Exception {
        if (prefix.getOperator() == PrefixExpression.Operator.MINUS && prefix.getOperand() instanceof NumberLiteral literal) {
            String token = literal.getToken().replace("_", "");
            if (token.equals("2147483648")) return value(context.vm().mirrorOf(Integer.MIN_VALUE));
            if (token.equalsIgnoreCase("9223372036854775808L")) return value(context.vm().mirrorOf(Long.MIN_VALUE));
        }
        Value operand = unbox(evaluate(prefix.getOperand(), context), context).value();
        PrefixExpression.Operator operator = prefix.getOperator();
        if (operator == PrefixExpression.Operator.NOT) {
            return value(context.vm().mirrorOf(!toBoolean(operand)));
        }
        Number number = toNumber(operand);
        if (operator == PrefixExpression.Operator.PLUS) {
            return value(mirrorNumber(context.vm(), number));
        }
        if (operator == PrefixExpression.Operator.MINUS) {
            if (number instanceof Double) return value(context.vm().mirrorOf(-number.doubleValue()));
            if (number instanceof Float) return value(context.vm().mirrorOf(-number.floatValue()));
            if (number instanceof Long) return value(context.vm().mirrorOf(-number.longValue()));
            return value(context.vm().mirrorOf(-number.intValue()));
        }
        if (operator == PrefixExpression.Operator.COMPLEMENT) {
            if (number instanceof Long) return value(context.vm().mirrorOf(~number.longValue()));
            return value(context.vm().mirrorOf(~number.intValue()));
        }
        throw new UnsupportedOperationException("Prefix operator is not implemented: " + operator);
    }

    private static EvalValue infix(InfixExpression infix, Context context) throws Exception {
        InfixExpression.Operator operator = infix.getOperator();
        EvalValue result = evaluate(infix.getLeftOperand(), context);
        List<Expression> remaining = new ArrayList<>();
        remaining.add(infix.getRightOperand());
        for (Object operand : infix.extendedOperands()) {
            remaining.add((Expression) operand);
        }
        for (Expression operand : remaining) {
            boolean equality = operator == InfixExpression.Operator.EQUALS || operator == InfixExpression.Operator.NOT_EQUALS;
            String rightType = JavaExpressionCompletion.staticTypeName(operand, context);
            boolean concatenation = operator == InfixExpression.Operator.PLUS
                    && (isString(result) || "java.lang.String".equals(rightType));
            if (!concatenation && (!equality || isPrimitive(result.typeName()) || isPrimitive(rightType))) {
                result = unbox(result, context);
            }
            if (operator == InfixExpression.Operator.CONDITIONAL_AND && !toBoolean(result.value())) {
                return value(context.vm().mirrorOf(false));
            }
            if (operator == InfixExpression.Operator.CONDITIONAL_OR && toBoolean(result.value())) {
                return value(context.vm().mirrorOf(true));
            }
            result = evaluateBinary(result, operator, evaluate(operand, context), context);
        }
        return result;
    }

    private static EvalValue evaluateBinary(
            EvalValue left,
            InfixExpression.Operator operator,
            EvalValue right,
            Context context
    ) throws Exception {
        VirtualMachine vm = context.vm();
        if (operator == InfixExpression.Operator.PLUS && (isString(left) || isString(right))) {
            return value(vm.mirrorOf(stringValue(left.value(), context) + stringValue(right.value(), context)));
        }
        boolean equality = operator == InfixExpression.Operator.EQUALS || operator == InfixExpression.Operator.NOT_EQUALS;
        if (!equality || left.value() instanceof PrimitiveValue || right.value() instanceof PrimitiveValue) {
            left = unbox(left, context);
            right = unbox(right, context);
        }
        if (operator == InfixExpression.Operator.CONDITIONAL_AND) {
            return value(vm.mirrorOf(toBoolean(left.value()) && toBoolean(right.value())));
        }
        if (operator == InfixExpression.Operator.CONDITIONAL_OR) {
            return value(vm.mirrorOf(toBoolean(left.value()) || toBoolean(right.value())));
        }
        if (operator == InfixExpression.Operator.EQUALS) {
            return value(vm.mirrorOf(equalsValue(left.value(), right.value())));
        }
        if (operator == InfixExpression.Operator.NOT_EQUALS) {
            return value(vm.mirrorOf(!equalsValue(left.value(), right.value())));
        }
        if (operator == InfixExpression.Operator.AND || operator == InfixExpression.Operator.OR
                || operator == InfixExpression.Operator.XOR || operator == InfixExpression.Operator.LEFT_SHIFT
                || operator == InfixExpression.Operator.RIGHT_SHIFT_SIGNED
                || operator == InfixExpression.Operator.RIGHT_SHIFT_UNSIGNED) {
            if (left.value() instanceof BooleanValue leftBoolean && right.value() instanceof BooleanValue rightBoolean) {
                boolean result = switch (operator.toString()) {
                    case "&" -> leftBoolean.value() & rightBoolean.value();
                    case "|" -> leftBoolean.value() | rightBoolean.value();
                    case "^" -> leftBoolean.value() ^ rightBoolean.value();
                    default -> throw new IllegalArgumentException("Boolean shift is invalid Java");
                };
                return value(vm.mirrorOf(result));
            }
            Number leftNumber = toNumber(left.value());
            Number rightNumber = toNumber(right.value());
            boolean shift = operator == InfixExpression.Operator.LEFT_SHIFT
                    || operator == InfixExpression.Operator.RIGHT_SHIFT_SIGNED
                    || operator == InfixExpression.Operator.RIGHT_SHIFT_UNSIGNED;
            if (leftNumber instanceof Long || !shift && rightNumber instanceof Long) {
                long a = leftNumber.longValue(), b = rightNumber.longValue();
                return value(vm.mirrorOf(switch (operator.toString()) {
                    case "&" -> a & b; case "|" -> a | b; case "^" -> a ^ b;
                    case "<<" -> a << b; case ">>" -> a >> b; default -> a >>> b;
                }));
            }
            int a = leftNumber.intValue(), b = rightNumber.intValue();
            return value(vm.mirrorOf(switch (operator.toString()) {
                case "&" -> a & b; case "|" -> a | b; case "^" -> a ^ b;
                case "<<" -> a << b; case ">>" -> a >> b; default -> a >>> b;
            }));
        }
        Number leftNumber = toNumber(left.value());
        Number rightNumber = toNumber(right.value());
        if ((operator == InfixExpression.Operator.LESS || operator == InfixExpression.Operator.LESS_EQUALS
                || operator == InfixExpression.Operator.GREATER || operator == InfixExpression.Operator.GREATER_EQUALS)
                && (Double.isNaN(leftNumber.doubleValue()) || Double.isNaN(rightNumber.doubleValue()))) {
            return value(vm.mirrorOf(false));
        }
        if (operator == InfixExpression.Operator.LESS) {
            return value(vm.mirrorOf(compare(leftNumber, rightNumber) < 0));
        }
        if (operator == InfixExpression.Operator.LESS_EQUALS) {
            return value(vm.mirrorOf(compare(leftNumber, rightNumber) <= 0));
        }
        if (operator == InfixExpression.Operator.GREATER) {
            return value(vm.mirrorOf(compare(leftNumber, rightNumber) > 0));
        }
        if (operator == InfixExpression.Operator.GREATER_EQUALS) {
            return value(vm.mirrorOf(compare(leftNumber, rightNumber) >= 0));
        }
        if (leftNumber instanceof Double || rightNumber instanceof Double) {
            double leftDouble = leftNumber.doubleValue();
            double rightDouble = rightNumber.doubleValue();
            return value(vm.mirrorOf(switch (operator.toString()) {
                case "+" -> leftDouble + rightDouble;
                case "-" -> leftDouble - rightDouble;
                case "*" -> leftDouble * rightDouble;
                case "/" -> leftDouble / rightDouble;
                default -> leftDouble % rightDouble;
            }));
        }
        if (leftNumber instanceof Float || rightNumber instanceof Float) {
            float a = leftNumber.floatValue(), b = rightNumber.floatValue();
            return value(vm.mirrorOf(switch (operator.toString()) {
                case "+" -> a + b; case "-" -> a - b; case "*" -> a * b;
                case "/" -> a / b; default -> a % b;
            }));
        }
        long leftLong = leftNumber.longValue();
        long rightLong = rightNumber.longValue();
        long result = switch (operator.toString()) {
            case "+" -> leftLong + rightLong;
            case "-" -> leftLong - rightLong;
            case "*" -> leftLong * rightLong;
            case "/" -> leftLong / rightLong;
            default -> leftLong % rightLong;
        };
        if (leftNumber instanceof Long || rightNumber instanceof Long) return value(vm.mirrorOf(result));
        return value(vm.mirrorOf((int) result));
    }

    private static boolean isString(EvalValue value) {
        return "java.lang.String".equals(value.typeName());
    }

    private static String stringValue(Value value, Context context) throws Exception {
        if (value == null) return "null";
        if (value instanceof StringReference string) return string.value();
        if (value instanceof CharValue character) return String.valueOf(character.charValue());
        if (value instanceof ObjectReference object) {
            Method toString = findMethod(object.referenceType(), "toString", "()Ljava/lang/String;", false);
            if (toString == null) throw new IllegalArgumentException("No toString method on " + object.referenceType().name());
            Value rendered = invoke(object, toString, List.of(), context, false);
            return rendered == null ? "null" : ((StringReference) rendered).value();
        }
        return value.toString();
    }

    private static EvalValue unbox(EvalValue value, Context context) throws Exception {
        if (value.value() instanceof PrimitiveValue) return value;
        DebuggerPrimitiveKind kind = DebuggerPrimitiveKind.fromTypeName(value.typeName());
        if (kind == null) throw new IllegalArgumentException("Primitive or wrapper value required: " + value.typeName());
        DebuggerEvaluationRunner.checkpoint();
        return value(DebuggerOverloadResolver.convertValue(value.value(), kind.primitiveName(), context));
    }

    static Integer constantInt(Expression expression, Context context) throws Exception {
        boolean[] literalOnly = {true};
        expression.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override public void preVisit(ASTNode node) {
                if (node instanceof Expression && !(node instanceof NumberLiteral || node instanceof CharacterLiteral
                        || node instanceof BooleanLiteral || node instanceof ParenthesizedExpression
                        || node instanceof PrefixExpression || node instanceof InfixExpression || node instanceof CastExpression)) {
                    literalOnly[0] = false;
                }
            }
        });
        if (!literalOnly[0]) return null;
        try {
            Value constant = evaluate(expression, context).value();
            return constant instanceof IntegerValue integer ? integer.intValue() : null;
        } catch (ArithmeticException invalidConstant) {
            return null;
        }
    }

    private static boolean equalsValue(Value left, Value right) {
        if (left == null || right == null) return left == right;
        if (left instanceof BooleanValue || right instanceof BooleanValue) {
            return toBoolean(left) == toBoolean(right);
        }
        if (left instanceof PrimitiveValue || right instanceof PrimitiveValue) {
            Number a = toNumber(left), b = toNumber(right);
            if (Double.isNaN(a.doubleValue()) || Double.isNaN(b.doubleValue())) return false;
            return compare(a, b) == 0;
        }
        return left instanceof ObjectReference leftObject
                && right instanceof ObjectReference rightObject
                && leftObject.uniqueID() == rightObject.uniqueID();
    }

    private static boolean toBoolean(Value value) {
        if (value instanceof BooleanValue booleanValue) {
            return booleanValue.booleanValue();
        }
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
        if (left instanceof Double || right instanceof Double) {
            double a = left.doubleValue(), b = right.doubleValue();
            return a == b ? 0 : Double.compare(a, b);
        }
        if (left instanceof Float || right instanceof Float) {
            float a = left.floatValue(), b = right.floatValue();
            return a == b ? 0 : Float.compare(a, b);
        }
        return Long.compare(left.longValue(), right.longValue());
    }

    private static Value mirrorNumber(VirtualMachine vm, String token) {
        String normalized = token.replace("_", "");
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("0x") || lower.startsWith("0b") || lower.startsWith("0") && normalized.length() > 1
                && !normalized.contains(".") && !lower.contains("e")) {
            throw new UnsupportedOperationException("Non-decimal numeric literals are not implemented");
        }
        char suffix = Character.toLowerCase(normalized.charAt(normalized.length() - 1));
        if (suffix == 'f') return vm.mirrorOf(Float.parseFloat(normalized.substring(0, normalized.length() - 1)));
        if (suffix == 'd') return vm.mirrorOf(Double.parseDouble(normalized.substring(0, normalized.length() - 1)));
        if (suffix == 'l') return vm.mirrorOf(Long.decode(normalized.substring(0, normalized.length() - 1)));
        if (normalized.contains(".") || normalized.contains("e") || normalized.contains("E")) {
            return vm.mirrorOf(Double.parseDouble(normalized));
        }
        return vm.mirrorOf(Integer.decode(normalized));
    }

    private static Value mirrorNumber(VirtualMachine vm, Number number) {
        if (number instanceof Double) return vm.mirrorOf(number.doubleValue());
        if (number instanceof Float) return vm.mirrorOf(number.floatValue());
        if (number instanceof Long) return vm.mirrorOf(number.longValue());
        return vm.mirrorOf(number.intValue());
    }

    static Expression parse(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Expression must not be blank");
        }
        ASTParser parser = JdtConfiguration.createParser();
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(source.toCharArray());
        ASTNode node = parser.createAST(null);
        if (!(node instanceof Expression expression) || (node.getFlags() & ASTNode.MALFORMED) != 0) {
            throw new IllegalArgumentException("Invalid Java expression: " + source);
        }
        var scanner = org.eclipse.jdt.core.ToolFactory.createScanner(false, false, false, "21");
        scanner.setSource(source.toCharArray());
        try {
            while (scanner.getNextToken() != org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameEOF) {
                if (scanner.getCurrentTokenStartPosition() < expression.getStartPosition()
                        || scanner.getCurrentTokenEndPosition() >= expression.getStartPosition() + expression.getLength()) {
                    throw new IllegalArgumentException("Trailing input after Java expression");
                }
            }
        } catch (org.eclipse.jdt.core.compiler.InvalidInputException invalid) {
            throw new IllegalArgumentException("Invalid Java expression", invalid);
        }
        return expression;
    }

    private static EvalValue value(Value value) {
        return new EvalValue(value, null, false);
    }

    private static EvalValue value(Value value, ReferenceType declaredType) {
        return new EvalValue(value, declaredType, false);
    }

    private static EvalValue type(ReferenceType type) {
        return new EvalValue(null, type, true);
    }

    static ReferenceType resolveType(String name, Context context) {
        ReferenceType exact = DebuggerJdiMembers.resolveType(name, context.vm());
        if (exact != null || DebuggerJdiMembers.isPrimitive(name)) {
            return exact;
        }
        ReferenceType declaringType = context.frame().location().declaringType();
        DebuggerTypeScope scope = context.evaluator().typeScopeResolver.scope(declaringType.name());
        return scope == null ? null : scope.resolve(name, declaringType, context.vm());
    }

    record EvalValue(Value value, ReferenceType type, boolean typeLiteral) {
        String typeName() {
            return type != null ? type.name() : value == null ? null : value.type().name();
        }
    }

    static final class Context {
        private final ObjectReference thisObject;
        private final JavaExpressionEvaluator evaluator;
        private final ThreadReference thread;
        private final int depth;
        private final com.sun.jdi.Location location;

        Context(StackFrame frame, ObjectReference thisObject, JavaExpressionEvaluator evaluator, ThreadReference thread) {
            this.thisObject = thisObject;
            this.evaluator = evaluator;
            this.thread = thread;
            this.location = frame.location();
            try {
                this.depth = thread.frames().indexOf(frame);
            } catch (com.sun.jdi.IncompatibleThreadStateException exception) {
                throw new IllegalStateException("Evaluation requires a suspended thread", exception);
            }
            if (this.depth < 0) throw new IllegalStateException("Selected frame is no longer available");
        }

        StackFrame frame() {
            try {
                StackFrame current = this.thread.frame(this.depth);
                if (!current.location().equals(this.location)) {
                    throw new IllegalStateException("Selected evaluation frame has changed");
                }
                return current;
            } catch (com.sun.jdi.IncompatibleThreadStateException exception) {
                throw new IllegalStateException("Evaluation frame is no longer suspended", exception);
            }
        }

        ReferenceType lexicalType() {
            ReferenceType declared = frame().location().declaringType();
            return this.thisObject != null && !isAssignable(this.thisObject.referenceType(), declared)
                    ? this.thisObject.referenceType() : declared;
        }

        ObjectReference thisObject() { return this.thisObject; }
        JavaExpressionEvaluator evaluator() { return this.evaluator; }
        ThreadReference thread() { return this.thread; }
        VirtualMachine vm() { return this.thread.virtualMachine(); }
        RichJavaExpressionEngine.VariableNameResolver variableNameResolver() { return this.evaluator.variableNameResolver; }
    }

    static final class TargetEvaluationException extends Exception {
        private TargetEvaluationException(String description, InvocationException cause) {
            super("Target method threw " + description, cause);
        }
    }

    @FunctionalInterface
    interface FrameRefresher {
        void refresh(ThreadReference thread);
    }
}
