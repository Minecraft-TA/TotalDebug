package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;

/** Conditional promotion uses metadata from both branches without invoking either branch. */
final class DebuggerConditionalType {
    private DebuggerConditionalType() { }

    static String resolve(ConditionalExpression expression, JavaExpressionEvaluator.Context context) throws Exception {
        String first = JavaExpressionCompletion.staticTypeName(expression.getThenExpression(), context);
        String second = JavaExpressionCompletion.staticTypeName(expression.getElseExpression(), context);
        if (first == null || second == null) throw unsupported();
        if (first.equals(second)) return first;
        var a = DebuggerPrimitiveKind.fromTypeName(first);
        var b = DebuggerPrimitiveKind.fromTypeName(second);
        if (a != null && b != null) {
            if (a == b) return a.primitiveName();
            if (a != DebuggerPrimitiveKind.BOOLEAN && b != DebuggerPrimitiveKind.BOOLEAN) {
                if (a == DebuggerPrimitiveKind.BYTE && b == DebuggerPrimitiveKind.SHORT
                        || b == DebuggerPrimitiveKind.BYTE && a == DebuggerPrimitiveKind.SHORT) return "short";
                if (second.equals("int") && narrows(expression.getElseExpression(), a, context)) return a.primitiveName();
                if (first.equals("int") && narrows(expression.getThenExpression(), b, context)) return b.primitiveName();
                return promote(a, b);
            }
        }
        String boxedFirst = DebuggerJdiMembers.isPrimitive(first) ? a == null ? first : a.boxedName() : first;
        String boxedSecond = DebuggerJdiMembers.isPrimitive(second) ? b == null ? second : b.boxedName() : second;
        if (first.equals("<null>")) return boxedSecond;
        if (second.equals("<null>")) return boxedFirst;
        if (DebuggerJdiMembers.isAssignableName(boxedFirst, boxedSecond, context.vm())) return boxedSecond;
        if (DebuggerJdiMembers.isAssignableName(boxedSecond, boxedFirst, context.vm())) return boxedFirst;
        throw unsupported();
    }

    private static boolean narrows(Expression expression, DebuggerPrimitiveKind target,
                                   JavaExpressionEvaluator.Context context) throws Exception {
        if (target != DebuggerPrimitiveKind.BYTE && target != DebuggerPrimitiveKind.SHORT
                && target != DebuggerPrimitiveKind.CHAR) return false;
        Integer constant = JavaExpressionEvaluator.constantInt(expression, context);
        if (constant == null) return false;
        return switch (target) {
            case BYTE -> constant >= Byte.MIN_VALUE && constant <= Byte.MAX_VALUE;
            case SHORT -> constant >= Short.MIN_VALUE && constant <= Short.MAX_VALUE;
            case CHAR -> constant >= Character.MIN_VALUE && constant <= Character.MAX_VALUE;
            default -> false;
        };
    }

    static String promote(DebuggerPrimitiveKind first, DebuggerPrimitiveKind second) {
        if (first == DebuggerPrimitiveKind.DOUBLE || second == DebuggerPrimitiveKind.DOUBLE) return "double";
        if (first == DebuggerPrimitiveKind.FLOAT || second == DebuggerPrimitiveKind.FLOAT) return "float";
        if (first == DebuggerPrimitiveKind.LONG || second == DebuggerPrimitiveKind.LONG) return "long";
        return "int";
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Cannot determine the conditional expression type from debugger metadata; use Code mode");
    }
}
