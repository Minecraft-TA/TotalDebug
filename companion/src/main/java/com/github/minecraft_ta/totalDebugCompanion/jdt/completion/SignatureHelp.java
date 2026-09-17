package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaAst;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import com.github.minecraft_ta.totalDebugCompanion.naming.MethodParameterNames;

import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.eclipse.jdt.core.compiler.IScanner;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.core.dom.*;

import java.util.*;

/** Call information from the same Java model used by completion, without evaluating the expression. */
public record SignatureHelp(int openingOffset, int argument, List<SignatureHelp.Signature> signatures) {
    public record Signature(String name, List<String> parameters, String resultType, boolean varargs, boolean selected) { }
    private record Call(int opening, List<Expression> arguments, IMethodBinding selected, ITypeBinding receiver,
                        String name, boolean constructor) { }

    public static SignatureHelp find(String className, String source, int caret) {
        CompilationUnit unit = JavaAst.parse(className, source);
        var scanner = ToolFactory.createScanner(false, false, false, JdtConfiguration.JAVA_VERSION);
        scanner.setSource(source.toCharArray());
        List<Call> calls = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            private void add(ASTNode node, int nameEnd, List<?> arguments, IMethodBinding binding, ITypeBinding receiver,
                             String name, boolean constructor) {
                if (nameEnd >= caret) return;
                int opening = opening(scanner, nameEnd, Math.min(source.length(), node.getStartPosition() + node.getLength()));
                if (opening < 0 || caret <= opening || caret > closing(scanner, source.length(), opening)) return;
                if (receiver == null && binding != null) receiver = binding.getDeclaringClass();
                if (receiver == null) {
                    for (ASTNode parent = node.getParent(); parent != null; parent = parent.getParent()) {
                        if (parent instanceof AbstractTypeDeclaration declaration) { receiver = declaration.resolveBinding(); break; }
                    }
                }
                if (receiver != null) calls.add(new Call(opening, arguments.stream().map(Expression.class::cast).toList(),
                        binding, receiver, name, constructor));
            }
            @Override public boolean visit(MethodInvocation node) {
                add(node, node.getName().getStartPosition() + node.getName().getLength(), node.arguments(), node.resolveMethodBinding(),
                        node.getExpression() == null ? null : node.getExpression().resolveTypeBinding(), node.getName().getIdentifier(), false);
                return true;
            }
            @Override public boolean visit(ClassInstanceCreation node) {
                add(node, node.getType().getStartPosition() + node.getType().getLength(), node.arguments(), node.resolveConstructorBinding(),
                        node.resolveTypeBinding(), "", true);
                return true;
            }
            @Override public boolean visit(SuperMethodInvocation node) {
                IMethodBinding binding = node.resolveMethodBinding();
                add(node, node.getName().getStartPosition() + node.getName().getLength(), node.arguments(), binding,
                        binding == null ? null : binding.getDeclaringClass(), node.getName().getIdentifier(), false);
                return true;
            }
        });
        Call call = calls.stream().max(Comparator.comparingInt(Call::opening)).orElse(null);
        if (call == null) return null;
        int active = argument(scanner, call, caret);
        Map<String, IMethodBinding> methods = new LinkedHashMap<>();
        if (call.selected() != null) methods.put(key(call.selected()), call.selected());
        collect(call.receiver(), call, methods, new HashSet<>());
        List<Signature> signatures = new ArrayList<>();
        for (var method : methods.values()) {
            if (method.getParameterTypes().length <= active && !method.isVarargs() && !(active == 0 && call.arguments().isEmpty())) continue;
            if (!acceptsCompletedArguments(method, call.arguments(), active)) continue;
            String[] names = names(unit, method);
            if (names == null) continue;
            List<String> parameters = new ArrayList<>();
            var types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                String type = types[i].getName();
                if (method.isVarargs() && i == types.length - 1 && type.endsWith("[]")) type = type.substring(0, type.length() - 2) + "...";
                parameters.add(type + " " + names[i]);
            }
            signatures.add(new Signature(method.isConstructor() ? method.getDeclaringClass().getName() : method.getName(),
                    List.copyOf(parameters), method.isConstructor() ? "" : method.getReturnType().getName(), method.isVarargs(),
                    call.selected() != null && key(method).equals(key(call.selected()))));
        }
        signatures.sort(Comparator.comparing(Signature::selected).reversed().thenComparingInt(signature -> signature.parameters().size()));
        return signatures.isEmpty() ? null : new SignatureHelp(call.opening(), active, List.copyOf(signatures));
    }

    private static void collect(ITypeBinding type, Call call, Map<String, IMethodBinding> methods, Set<String> visited) {
        if (type == null || !visited.add(type.getKey())) return;
        for (var method : type.getDeclaredMethods()) {
            if (method.isConstructor() == call.constructor() && (call.constructor() || method.getName().equals(call.name()))) {
                methods.putIfAbsent(key(method), method);
            }
        }
        if (!call.constructor()) {
            collect(type.getSuperclass(), call, methods, visited);
            for (var parent : type.getInterfaces()) collect(parent, call, methods, visited);
        }
    }

    private static String key(IMethodBinding method) {
        return method.getName() + Arrays.toString(Arrays.stream(method.getParameterTypes()).map(ITypeBinding::getQualifiedName).toArray());
    }

    private static boolean acceptsCompletedArguments(IMethodBinding method, List<Expression> arguments, int active) {
        var parameters = method.getParameterTypes();
        for (int i = 0; i < Math.min(active, arguments.size()); i++) {
            var actual = arguments.get(i).resolveTypeBinding();
            if (actual == null || actual.isRecovered()) continue;
            if (parameters.length == 0 || i >= parameters.length && !method.isVarargs()) return false;
            var expected = parameters[Math.min(i, parameters.length - 1)];
            if (actual.isAssignmentCompatible(expected)) continue;
            if (method.isVarargs() && i >= parameters.length - 1 && actual.isAssignmentCompatible(expected.getComponentType())) continue;
            return false;
        }
        return true;
    }

    private static String[] names(CompilationUnit unit, IMethodBinding method) {
        if (unit.findDeclaringNode(method.getMethodDeclaration()) instanceof MethodDeclaration declaration) {
            return (String[]) declaration.parameters().stream().map(parameter -> ((SingleVariableDeclaration) parameter).getName().getIdentifier()).toArray(String[]::new);
        }
        if (!(JavaSymbolResolver.trySymbolForBinding(method) instanceof CodeSymbol.MethodSymbol symbol)) return null;
        String[] raw = CompanionClassIndex.parameterNames(symbol.ownerClassName().replace('.', '/'), symbol.name(), symbol.descriptor());
        if (raw.length == 0) raw = MethodParameterNames.resolve(new MethodParameterNames.Method(
                symbol.ownerClassName().replace('.', '/'), symbol.name(), symbol.descriptor(), method.getModifiers(), null), null);
        int count = method.getParameterTypes().length;
        int prefix = method.isConstructor() ? raw.length - count : 0;
        return prefix >= 0 && raw.length >= prefix + count ? Arrays.copyOfRange(raw, prefix, prefix + count) : null;
    }

    private static int opening(IScanner scanner, int start, int end) {
        scanner.resetTo(start, end - 1);
        try {
            int token;
            while ((token = scanner.getNextToken()) != ITerminalSymbols.TokenNameEOF) {
                if (token == ITerminalSymbols.TokenNameLPAREN) return scanner.getCurrentTokenStartPosition();
            }
        } catch (InvalidInputException ignored) { }
        return -1;
    }

    private static int closing(IScanner scanner, int sourceLength, int opening) {
        scanner.resetTo(opening, sourceLength - 1);
        int depth = 0;
        try {
            int token;
            while ((token = scanner.getNextToken()) != ITerminalSymbols.TokenNameEOF) {
                if (token == ITerminalSymbols.TokenNameLPAREN) depth++;
                if (token == ITerminalSymbols.TokenNameRPAREN && --depth == 0) return scanner.getCurrentTokenStartPosition();
            }
        } catch (InvalidInputException ignored) { }
        return sourceLength;
    }

    private static int argument(IScanner scanner, Call call, int caret) {
        scanner.resetTo(call.opening() + 1, caret - 1);
        int argument = 0;
        try {
            int token;
            while ((token = scanner.getNextToken()) != ITerminalSymbols.TokenNameEOF) {
                int offset = scanner.getCurrentTokenStartPosition();
                if (token == ITerminalSymbols.TokenNameCOMMA && call.arguments().stream().noneMatch(expression ->
                        offset >= expression.getStartPosition() && offset < expression.getStartPosition() + expression.getLength())) argument++;
            }
        } catch (InvalidInputException ignored) { }
        return argument;
    }
}
