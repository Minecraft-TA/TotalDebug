package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.microsoft.java.debug.core.IEvaluatableBreakpoint;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.ThisExpression;

import java.util.List;
import java.util.concurrent.CompletableFuture;

final class SimpleJdiEvaluationProvider implements IEvaluationProvider {
    @Override
    public boolean isInEvaluation(ThreadReference thread) {
        return false;
    }

    @Override
    public CompletableFuture<Value> evaluate(String expression, ThreadReference thread, int depth) {
        try {
            StackFrame frame = thread.frame(depth);
            return CompletableFuture.completedFuture(evaluate(parse(expression), new Context(frame, frame.thisObject())));
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletableFuture<Value> evaluate(
            String expression,
            ObjectReference thisContext,
            ThreadReference thread
    ) {
        try {
            return CompletableFuture.completedFuture(evaluate(
                    parse(expression),
                    new Context(thread.frame(0), thisContext)
            ));
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletableFuture<Value> evaluateForBreakpoint(
            IEvaluatableBreakpoint breakpoint,
            ThreadReference thread
    ) {
        if (breakpoint.containsLogpointExpression()) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "Logpoint expression evaluation is not implemented"
            ));
        }
        return evaluate(breakpoint.getCondition(), thread, 0);
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
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Method invocation during evaluation is not implemented"
        ));
    }

    @Override
    public void clearState(ThreadReference thread) {
    }

    private static Expression parse(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Expression must not be blank");
        }
        ASTParser parser = ASTParser.newParser(AST.JLS21);
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(source.toCharArray());
        ASTNode node = parser.createAST(null);
        if (!(node instanceof Expression expression) || (node.getFlags() & ASTNode.MALFORMED) != 0) {
            throw new IllegalArgumentException("Invalid Java expression: " + source);
        }
        return expression;
    }

    private static Value evaluate(Expression expression, Context context) throws Exception {
        if (expression instanceof ParenthesizedExpression parenthesized) {
            return evaluate(parenthesized.getExpression(), context);
        }
        if (expression instanceof BooleanLiteral literal) {
            return context.vm().mirrorOf(literal.booleanValue());
        }
        if (expression instanceof CharacterLiteral literal) {
            return context.vm().mirrorOf(literal.charValue());
        }
        if (expression instanceof StringLiteral literal) {
            return context.vm().mirrorOf(literal.getLiteralValue());
        }
        if (expression instanceof NumberLiteral literal) {
            return mirrorNumber(context.vm(), literal.getToken());
        }
        if (expression instanceof NullLiteral) {
            return null;
        }
        if (expression instanceof ThisExpression) {
            if (context.thisObject() == null) {
                throw new IllegalArgumentException("'this' is unavailable in the selected frame");
            }
            return context.thisObject();
        }
        if (expression instanceof SimpleName name) {
            return simpleName(name.getIdentifier(), context);
        }
        if (expression instanceof FieldAccess access) {
            return field(evaluate(access.getExpression(), context), access.getName().getIdentifier());
        }
        if (expression instanceof QualifiedName name) {
            return field(evaluate(name.getQualifier(), context), name.getName().getIdentifier());
        }
        if (expression instanceof ArrayAccess access) {
            Value arrayValue = evaluate(access.getArray(), context);
            if (!(arrayValue instanceof ArrayReference array)) {
                throw new IllegalArgumentException("Array access requires an array value");
            }
            int index = toNumber(evaluate(access.getIndex(), context)).intValue();
            return array.getValue(index);
        }
        if (expression instanceof PrefixExpression prefix) {
            return prefix(prefix, context);
        }
        if (expression instanceof InfixExpression infix) {
            return infix(infix, context);
        }
        if (expression instanceof ConditionalExpression conditional) {
            return toBoolean(evaluate(conditional.getExpression(), context))
                    ? evaluate(conditional.getThenExpression(), context)
                    : evaluate(conditional.getElseExpression(), context);
        }
        throw new UnsupportedOperationException(
                "Expression type is not implemented: " + expression.getClass().getSimpleName()
        );
    }

    private static Value simpleName(String name, Context context) throws Exception {
        if (context.frame() != null) {
            LocalVariable local = context.frame().visibleVariableByName(name);
            if (local != null) {
                return context.frame().getValue(local);
            }
        }
        if (context.thisObject() != null) {
            Field instanceField = context.thisObject().referenceType().fieldByName(name);
            if (instanceField != null) {
                return context.thisObject().getValue(instanceField);
            }
        }
        if (context.frame() != null) {
            ReferenceType declaringType = context.frame().location().declaringType();
            Field staticField = declaringType.fieldByName(name);
            if (staticField != null && staticField.isStatic()) {
                return declaringType.getValue(staticField);
            }
        }
        throw new IllegalArgumentException("Unknown variable or field: " + name);
    }

    private static Value field(Value owner, String name) {
        if (owner == null) {
            throw new IllegalArgumentException("Cannot read field '" + name + "' from null");
        }
        if (owner instanceof ArrayReference array && name.equals("length")) {
            return array.virtualMachine().mirrorOf(array.length());
        }
        if (!(owner instanceof ObjectReference object)) {
            throw new IllegalArgumentException("Field access requires an object value");
        }
        Field field = object.referenceType().fieldByName(name);
        if (field == null) {
            throw new IllegalArgumentException(
                    "Unknown field " + object.referenceType().name() + "." + name
            );
        }
        return object.getValue(field);
    }

    private static Value prefix(PrefixExpression prefix, Context context) throws Exception {
        Value operand = evaluate(prefix.getOperand(), context);
        PrefixExpression.Operator operator = prefix.getOperator();
        if (operator == PrefixExpression.Operator.NOT) {
            return context.vm().mirrorOf(!toBoolean(operand));
        }
        Number number = toNumber(operand);
        if (operator == PrefixExpression.Operator.PLUS) {
            return mirrorNumber(context.vm(), number);
        }
        if (operator == PrefixExpression.Operator.MINUS) {
            return isFloating(number)
                    ? context.vm().mirrorOf(-number.doubleValue())
                    : context.vm().mirrorOf(-number.longValue());
        }
        if (operator == PrefixExpression.Operator.COMPLEMENT) {
            return context.vm().mirrorOf(~number.longValue());
        }
        throw new UnsupportedOperationException("Prefix operator is not implemented: " + operator);
    }

    private static Value infix(InfixExpression infix, Context context) throws Exception {
        Value left = evaluate(infix.getLeftOperand(), context);
        if (infix.getOperator() == InfixExpression.Operator.CONDITIONAL_AND && !toBoolean(left)) {
            return context.vm().mirrorOf(false);
        }
        if (infix.getOperator() == InfixExpression.Operator.CONDITIONAL_OR && toBoolean(left)) {
            return context.vm().mirrorOf(true);
        }
        Value value = evaluateBinary(
                left,
                infix.getOperator(),
                evaluate(infix.getRightOperand(), context),
                context.vm()
        );
        @SuppressWarnings("unchecked")
        List<Expression> extended = infix.extendedOperands();
        for (Expression operand : extended) {
            value = evaluateBinary(value, infix.getOperator(), evaluate(operand, context), context.vm());
        }
        return value;
    }

    private static Value evaluateBinary(
            Value left,
            InfixExpression.Operator operator,
            Value right,
            VirtualMachine vm
    ) {
        if (operator == InfixExpression.Operator.CONDITIONAL_AND) {
            return vm.mirrorOf(toBoolean(left) && toBoolean(right));
        }
        if (operator == InfixExpression.Operator.CONDITIONAL_OR) {
            return vm.mirrorOf(toBoolean(left) || toBoolean(right));
        }
        if (operator == InfixExpression.Operator.EQUALS) {
            return vm.mirrorOf(equalsValue(left, right));
        }
        if (operator == InfixExpression.Operator.NOT_EQUALS) {
            return vm.mirrorOf(!equalsValue(left, right));
        }

        Number leftNumber = toNumber(left);
        Number rightNumber = toNumber(right);
        if (operator == InfixExpression.Operator.LESS) {
            return vm.mirrorOf(compare(leftNumber, rightNumber) < 0);
        }
        if (operator == InfixExpression.Operator.LESS_EQUALS) {
            return vm.mirrorOf(compare(leftNumber, rightNumber) <= 0);
        }
        if (operator == InfixExpression.Operator.GREATER) {
            return vm.mirrorOf(compare(leftNumber, rightNumber) > 0);
        }
        if (operator == InfixExpression.Operator.GREATER_EQUALS) {
            return vm.mirrorOf(compare(leftNumber, rightNumber) >= 0);
        }

        boolean floating = isFloating(leftNumber) || isFloating(rightNumber);
        if (operator == InfixExpression.Operator.PLUS) {
            return floating
                    ? vm.mirrorOf(leftNumber.doubleValue() + rightNumber.doubleValue())
                    : vm.mirrorOf(leftNumber.longValue() + rightNumber.longValue());
        }
        if (operator == InfixExpression.Operator.MINUS) {
            return floating
                    ? vm.mirrorOf(leftNumber.doubleValue() - rightNumber.doubleValue())
                    : vm.mirrorOf(leftNumber.longValue() - rightNumber.longValue());
        }
        if (operator == InfixExpression.Operator.TIMES) {
            return floating
                    ? vm.mirrorOf(leftNumber.doubleValue() * rightNumber.doubleValue())
                    : vm.mirrorOf(leftNumber.longValue() * rightNumber.longValue());
        }
        if (operator == InfixExpression.Operator.DIVIDE) {
            return floating
                    ? vm.mirrorOf(leftNumber.doubleValue() / rightNumber.doubleValue())
                    : vm.mirrorOf(leftNumber.longValue() / rightNumber.longValue());
        }
        if (operator == InfixExpression.Operator.REMAINDER) {
            return floating
                    ? vm.mirrorOf(leftNumber.doubleValue() % rightNumber.doubleValue())
                    : vm.mirrorOf(leftNumber.longValue() % rightNumber.longValue());
        }
        throw new UnsupportedOperationException("Infix operator is not implemented: " + operator);
    }

    private static boolean equalsValue(Value left, Value right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof PrimitiveValue || right instanceof PrimitiveValue) {
            if (left instanceof BooleanValue || right instanceof BooleanValue) {
                return toBoolean(left) == toBoolean(right);
            }
            return compare(toNumber(left), toNumber(right)) == 0;
        }
        if (left instanceof ObjectReference leftObject && right instanceof ObjectReference rightObject) {
            return leftObject.uniqueID() == rightObject.uniqueID();
        }
        return false;
    }

    private static boolean toBoolean(Value value) {
        if (value instanceof BooleanValue booleanValue) {
            return booleanValue.booleanValue();
        }
        throw new IllegalArgumentException("Boolean expression required");
    }

    private static Number toNumber(Value value) {
        if (value instanceof ByteValue number) {
            return number.byteValue();
        }
        if (value instanceof ShortValue number) {
            return number.shortValue();
        }
        if (value instanceof IntegerValue number) {
            return number.intValue();
        }
        if (value instanceof LongValue number) {
            return number.longValue();
        }
        if (value instanceof FloatValue number) {
            return number.floatValue();
        }
        if (value instanceof DoubleValue number) {
            return number.doubleValue();
        }
        if (value instanceof CharValue character) {
            return (int) character.charValue();
        }
        throw new IllegalArgumentException("Numeric expression required");
    }

    private static int compare(Number left, Number right) {
        return isFloating(left) || isFloating(right)
                ? Double.compare(left.doubleValue(), right.doubleValue())
                : Long.compare(left.longValue(), right.longValue());
    }

    private static boolean isFloating(Number number) {
        return number instanceof Float || number instanceof Double;
    }

    private static Value mirrorNumber(VirtualMachine vm, String token) {
        String normalized = token.replace("_", "");
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        boolean nonDecimalInteger = lower.startsWith("0x")
                || lower.startsWith("0b")
                || (lower.length() > 1
                && lower.charAt(0) == '0'
                && !lower.contains(".")
                && !lower.contains("e"));
        if (nonDecimalInteger) {
            throw new UnsupportedOperationException(
                    "Non-decimal numeric literals are not implemented"
            );
        }
        char suffix = Character.toLowerCase(normalized.charAt(normalized.length() - 1));
        if (suffix == 'f') {
            return vm.mirrorOf(Float.parseFloat(normalized.substring(0, normalized.length() - 1)));
        }
        if (suffix == 'd') {
            return vm.mirrorOf(Double.parseDouble(normalized.substring(0, normalized.length() - 1)));
        }
        if (suffix == 'l') {
            return vm.mirrorOf(Long.decode(normalized.substring(0, normalized.length() - 1)));
        }
        if (normalized.contains(".") || normalized.contains("e") || normalized.contains("E")) {
            return vm.mirrorOf(Double.parseDouble(normalized));
        }
        return vm.mirrorOf(Integer.decode(normalized));
    }

    private static Value mirrorNumber(VirtualMachine vm, Number number) {
        return isFloating(number) ? vm.mirrorOf(number.doubleValue()) : vm.mirrorOf(number.longValue());
    }

    private record Context(StackFrame frame, ObjectReference thisObject) {
        private VirtualMachine vm() {
            if (this.frame != null) {
                return this.frame.virtualMachine();
            }
            return this.thisObject.virtualMachine();
        }
    }
}
