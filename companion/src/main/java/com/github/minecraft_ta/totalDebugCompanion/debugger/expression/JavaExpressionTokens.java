package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Method;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;

import java.util.ArrayList;
import java.util.List;

import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.findField;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.methods;

/** Semantic token classification for a parsed debugger expression. */
final class JavaExpressionTokens {
    private JavaExpressionTokens() {
    }

    static List<DebugEngine.ExpressionToken> classify(
            String expression,
            JavaExpressionEvaluator.Context context
    ) {
        List<DebugEngine.ExpressionToken> result = new ArrayList<>();
        JavaExpressionEvaluator.parse(expression).accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                DebugEngine.ExpressionTokenKind kind;
                try {
                    kind = expressionTokenKind(node, context);
                } catch (Exception ignored) {
                    return true;
                }
                if (kind != null) {
                    result.add(new DebugEngine.ExpressionToken(
                            node.getStartPosition(), node.getLength(), kind));
                }
                return true;
            }
        });
        return List.copyOf(result);
    }

    private static DebugEngine.ExpressionTokenKind expressionTokenKind(
            SimpleName name,
            JavaExpressionEvaluator.Context context
    ) throws Exception {
        ASTNode parent = name.getParent();
        if (parent instanceof MethodInvocation invocation && invocation.getName() == name) {
            JavaExpressionCompletion.CompletionOwner owner = invocation.getExpression() == null
                    ? new JavaExpressionCompletion.CompletionOwner(
                    context.frame().location().declaringType(), false, false)
                    : JavaExpressionCompletion.resolveCompletionOwner(invocation.getExpression(), context);
            return hasMethod(owner, name.getIdentifier()) ? DebugEngine.ExpressionTokenKind.METHOD : null;
        }
        if (parent instanceof SuperMethodInvocation invocation && invocation.getName() == name) {
            return methods(JavaExpressionCompletion.lexicalSuperclass(context)).stream()
                    .anyMatch(method -> method.name().equals(name.getIdentifier()))
                    ? DebugEngine.ExpressionTokenKind.METHOD
                    : null;
        }

        boolean qualifiedMember = parent instanceof QualifiedName qualified && qualified.getName() == name
                || parent instanceof FieldAccess fieldAccess && fieldAccess.getName() == name
                || parent instanceof SuperFieldAccess superFieldAccess && superFieldAccess.getName() == name;
        if (!qualifiedMember) {
            Field lexicalField = lexicalField(name.getIdentifier(), context);
            if (lexicalField != null) {
                return DebugEngine.ExpressionTokenKind.FIELD;
            }
            if (visibleLocal(name.getIdentifier(), context) != null) {
                return null;
            }
        }

        String typeName = name.getIdentifier();
        if (parent instanceof QualifiedName qualified && qualified.getName() == name) {
            typeName = qualified.getFullyQualifiedName();
        }
        if (JavaExpressionEvaluator.resolveType(typeName, context) != null) {
            return DebugEngine.ExpressionTokenKind.TYPE;
        }

        Field member = memberField(name, context);
        return member == null ? null : DebugEngine.ExpressionTokenKind.FIELD;
    }

    private static boolean hasMethod(JavaExpressionCompletion.CompletionOwner owner, String name) {
        return owner != null && owner.type() != null && methods(owner.type()).stream()
                .anyMatch(method -> method.name().equals(name)
                        && (!owner.typeLiteral() || method.isStatic()));
    }

    private static LocalVariable visibleLocal(
            String displayedName,
            JavaExpressionEvaluator.Context context
    ) throws AbsentInformationException {
        Method method = context.frame().location().method();
        String binaryName = context.frame().location().declaringType().name();
        for (LocalVariable local : context.frame().visibleVariables()) {
            if (local.name().equals(displayedName) || context.variableNameResolver().displayedName(
                    binaryName, method.name(), method.signature(), local.name()).equals(displayedName)) {
                return local;
            }
        }
        return null;
    }

    private static Field lexicalField(String name, JavaExpressionEvaluator.Context context) {
        if (context.thisObject() != null) {
            Field field = findField(context.thisObject().referenceType(), name, false);
            if (field != null) {
                return field;
            }
        }
        return findField(context.frame().location().declaringType(), name, true);
    }

    private static Field memberField(
            SimpleName name,
            JavaExpressionEvaluator.Context context
    ) throws Exception {
        ASTNode parent = name.getParent();
        JavaExpressionCompletion.CompletionOwner owner;
        if (parent instanceof QualifiedName qualified && qualified.getName() == name) {
            owner = JavaExpressionCompletion.resolveCompletionOwner(qualified.getQualifier(), context);
        } else if (parent instanceof FieldAccess access && access.getName() == name) {
            owner = JavaExpressionCompletion.resolveCompletionOwner(access.getExpression(), context);
        } else if (parent instanceof SuperFieldAccess access && access.getName() == name) {
            owner = new JavaExpressionCompletion.CompletionOwner(
                    JavaExpressionCompletion.lexicalSuperclass(context), false, false);
        } else {
            return null;
        }
        return owner == null || owner.type() == null
                ? null
                : findField(owner.type(), name.getIdentifier(), owner.typeLiteral());
    }
}
