package com.github.minecraft_ta.totalDebugCompanion.jdt.symbol;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.VariableDeclaration;

import java.util.Arrays;
import java.util.Objects;

/** Resolves editor selections once for navigation, implementations, and reference search. */
public final class JavaSymbolResolver {
    private JavaSymbolResolver() {
    }

    public static IJavaElement selectElement(String editorIdentifier, int offset) throws JavaModelException {
        CompilationUnit unit = ASTCache.getFromCache(editorIdentifier);
        if (unit == null) {
            return null;
        }

        IJavaElement[] elements = unit.getTypeRoot().codeSelect(offset, 0);
        if (elements == null || elements.length == 0) {
            return null;
        }
        if (elements.length != 1) {
            System.err.println("Multiple Java elements found at offset " + offset + ": " + Arrays.toString(elements));
            return null;
        }
        return elements[0];
    }

    public static Resolution resolve(String editorIdentifier, int offset) throws JavaModelException {
        CompilationUnit unit = ASTCache.getFromCache(editorIdentifier);
        if (unit == null) {
            return Resolution.unavailable("Java model is still loading");
        }
        return resolve(unit, offset);
    }

    static Resolution resolve(CompilationUnit unit, int offset) throws JavaModelException {
        Objects.requireNonNull(unit, "unit");
        IJavaElement[] elements = unit.getTypeRoot().codeSelect(offset, 0);
        if (elements == null || elements.length == 0) {
            return Resolution.unavailable("Place the caret on a class, field, or method");
        }
        if (elements.length != 1) {
            return Resolution.unavailable("The Java model resolved multiple symbols at this position");
        }

        IJavaElement element = elements[0];
        if (element.getElementType() == IJavaElement.LOCAL_VARIABLE) {
            return Resolution.unavailable("Local-variable usages are not present in runtime bytecode");
        }
        if (element instanceof IType type) {
            return resolvedType(type);
        }
        if (!(element instanceof IMethod) && !(element instanceof IField)) {
            return Resolution.unavailable("Find Usages supports classes, fields, and methods");
        }

        IBinding binding = resolveBinding(unit, element, offset);
        if (binding == null) {
            return Resolution.unavailable("JDT could not resolve this symbol to runtime bytecode");
        }
        try {
            return switch (binding) {
                case IMethodBinding method -> Resolution.resolved(methodSymbol(method));
                case IVariableBinding variable when variable.isField() ->
                        Resolution.resolved(fieldSymbol(variable));
                default -> Resolution.unavailable("Find Usages supports classes, fields, and methods");
            };
        } catch (UnresolvedBindingException exception) {
            return Resolution.unavailable(exception.getMessage());
        }
    }

    private static Resolution resolvedType(IType type) throws JavaModelException {
        String binaryName = type.getFullyQualifiedName('$');
        if (binaryName.isBlank()) {
            return Resolution.unavailable("JDT could not determine the runtime class name");
        }
        return Resolution.resolved(new CodeSymbol.ClassSymbol(binaryName));
    }

    private static IBinding resolveBinding(CompilationUnit unit, IJavaElement element, int offset) {
        ASTNode current = NodeFinder.perform(unit, offset, 0);
        while (current != null) {
            IBinding binding = switch (current) {
                case MethodDeclaration declaration when element instanceof IMethod -> declaration.resolveBinding();
                case MethodInvocation invocation when element instanceof IMethod -> invocation.resolveMethodBinding();
                case SuperMethodInvocation invocation when element instanceof IMethod -> invocation.resolveMethodBinding();
                case MethodReference reference when element instanceof IMethod -> reference.resolveMethodBinding();
                case ClassInstanceCreation creation when element instanceof IMethod -> creation.resolveConstructorBinding();
                case ConstructorInvocation invocation when element instanceof IMethod -> invocation.resolveConstructorBinding();
                case SuperConstructorInvocation invocation when element instanceof IMethod -> invocation.resolveConstructorBinding();
                case EnumConstantDeclaration declaration when element instanceof IMethod -> declaration.resolveConstructorBinding();
                case VariableDeclaration declaration when element instanceof IField -> declaration.resolveBinding();
                case FieldAccess access when element instanceof IField -> access.resolveFieldBinding();
                case SuperFieldAccess access when element instanceof IField -> access.resolveFieldBinding();
                case Name name -> name.resolveBinding();
                default -> null;
            };
            if (bindingMatchesElement(binding, element)) {
                return binding;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean bindingMatchesElement(IBinding binding, IJavaElement element) {
        return (element instanceof IMethod && binding instanceof IMethodBinding)
                || (element instanceof IField && binding instanceof IVariableBinding variable && variable.isField());
    }

    private static CodeSymbol.MethodSymbol methodSymbol(IMethodBinding selectedMethod) {
        IMethodBinding method = selectedMethod.getMethodDeclaration();
        String owner = binaryName(method.getDeclaringClass());
        String name = method.isConstructor() ? "<init>" : method.getName();
        StringBuilder descriptor = new StringBuilder("(");
        for (ITypeBinding parameter : method.getParameterTypes()) {
            descriptor.append(descriptor(parameter));
        }
        descriptor.append(')');
        descriptor.append(method.isConstructor() ? 'V' : descriptor(method.getReturnType()));
        return new CodeSymbol.MethodSymbol(owner, name, descriptor.toString());
    }

    private static CodeSymbol.FieldSymbol fieldSymbol(IVariableBinding selectedField) {
        IVariableBinding field = selectedField.getVariableDeclaration();
        return new CodeSymbol.FieldSymbol(
                binaryName(field.getDeclaringClass()),
                field.getName(),
                descriptor(field.getType())
        );
    }

    private static String descriptor(ITypeBinding originalType) {
        if (originalType == null) {
            throw new UnresolvedBindingException("JDT could not determine the runtime member type");
        }
        ITypeBinding type = originalType.getErasure();
        if (type.isArray()) {
            return "[".repeat(type.getDimensions()) + descriptor(type.getElementType());
        }
        if (type.isPrimitive()) {
            return switch (type.getName()) {
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "double" -> "D";
                case "float" -> "F";
                case "int" -> "I";
                case "long" -> "J";
                case "short" -> "S";
                case "void" -> "V";
                default -> throw new UnresolvedBindingException("Unknown primitive type " + type.getName());
            };
        }
        return 'L' + binaryName(type).replace('.', '/') + ';';
    }

    private static String binaryName(ITypeBinding originalType) {
        if (originalType == null) {
            throw new UnresolvedBindingException("JDT could not determine the declaring runtime class");
        }
        String binaryName = originalType.getErasure().getBinaryName();
        if (binaryName == null || binaryName.isBlank()) {
            throw new UnresolvedBindingException("JDT could not determine the declaring runtime class");
        }
        return binaryName;
    }

    public record Resolution(CodeSymbol symbol, String unavailableReason) {
        public Resolution {
            if ((symbol == null) == (unavailableReason == null)) {
                throw new IllegalArgumentException("A symbol resolution must contain exactly one outcome");
            }
        }

        public static Resolution resolved(CodeSymbol symbol) {
            return new Resolution(Objects.requireNonNull(symbol, "symbol"), null);
        }

        public static Resolution unavailable(String reason) {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("Unavailable reason must not be blank");
            }
            return new Resolution(null, reason);
        }

        public boolean isResolved() {
            return this.symbol != null;
        }
    }

    private static final class UnresolvedBindingException extends RuntimeException {
        private UnresolvedBindingException(String message) {
            super(message);
        }
    }
}
