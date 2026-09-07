package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import org.eclipse.jdt.core.dom.*;

/** Checks the complete tree before the interpreter can invoke target code. */
final class JavaExpressionSupport {
    private JavaExpressionSupport() { }

    static void requireSupported(Expression expression) {
        expression.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if ((node.getFlags() & (ASTNode.MALFORMED | ASTNode.RECOVERED)) != 0) {
                    throw new IllegalArgumentException("Invalid Java syntax: " + node);
                }
                if (!(node instanceof Expression value)) return;
                if (!(value instanceof ParenthesizedExpression || value instanceof BooleanLiteral
                        || value instanceof CharacterLiteral || value instanceof StringLiteral
                        || value instanceof NumberLiteral || value instanceof NullLiteral
                        || value instanceof ThisExpression || value instanceof SimpleName
                        || value instanceof QualifiedName || value instanceof FieldAccess
                        || value instanceof SuperFieldAccess || value instanceof ArrayAccess
                        || value instanceof PrefixExpression || value instanceof InfixExpression
                        || value instanceof ConditionalExpression || value instanceof MethodInvocation
                        || value instanceof SuperMethodInvocation || value instanceof CastExpression
                        || value instanceof InstanceofExpression || value instanceof TypeLiteral)) {
                    unsupported(value);
                }
                if (value instanceof PrefixExpression prefix
                        && (prefix.getOperator() == PrefixExpression.Operator.INCREMENT
                        || prefix.getOperator() == PrefixExpression.Operator.DECREMENT)) unsupported(value);
                if (value instanceof ThisExpression current && current.getQualifier() != null) unsupported(value);
                if (value instanceof SuperFieldAccess access && access.getQualifier() != null) unsupported(value);
                if (value instanceof MethodInvocation call && !call.typeArguments().isEmpty()) unsupported(value);
                if (value instanceof SuperMethodInvocation call
                        && (!call.typeArguments().isEmpty() || call.getQualifier() != null)) unsupported(value);
                if (value instanceof TypeLiteral literal && (literal.getType().isPrimitiveType() || literal.getType().isArrayType())) unsupported(value);
                if (value instanceof NumberLiteral literal) {
                    String token = literal.getToken().replace("_", "").toLowerCase(java.util.Locale.ROOT);
                    if (token.startsWith("0x") || token.startsWith("0b")
                            || token.startsWith("0") && token.length() > 1 && !token.contains(".")
                            && !token.contains("e")) unsupported(value);
                    boolean negative = literal.getParent() instanceof PrefixExpression prefix
                            && prefix.getOperator() == PrefixExpression.Operator.MINUS;
                    try {
                        if (token.endsWith("l")) {
                            if (!(negative && token.equals("9223372036854775808l"))) Long.parseLong(token.substring(0, token.length() - 1));
                        } else if (!token.contains(".") && !token.contains("e") && !token.endsWith("f") && !token.endsWith("d")) {
                            if (!(negative && token.equals("2147483648"))) Integer.parseInt(token);
                        }
                    } catch (NumberFormatException outsideInterpreterRange) { unsupported(value); }
                }
            }
        });
    }

    private static void unsupported(Expression expression) {
        throw new UnsupportedOperationException("Java compilation is required for "
                + expression.getClass().getSimpleName() + ": " + expression);
    }
}
