package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import com.github.minecraft_ta.totaldebug.evaluation.InMemoryJavaCompiler;
import com.github.minecraft_ta.totaldebug.evaluation.PausedEvaluationBridge;
import com.sun.jdi.*;
import org.eclipse.jdt.core.dom.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.*;
import java.util.function.Supplier;

/** Compiles a whole fragment before installing or invoking any of it in the debuggee. */
final class CompiledFrameEvaluator {
    private final Supplier<String> classpath;
    private final RichJavaExpressionEngine.VariableNameResolver names;
    private final RichJavaExpressionEngine.TypeScopeResolver scopes;

    CompiledFrameEvaluator(Supplier<String> classpath, RichJavaExpressionEngine.VariableNameResolver names,
                           RichJavaExpressionEngine.TypeScopeResolver scopes) {
        this.classpath = classpath;
        this.names = names;
        this.scopes = scopes;
    }

    Value evaluate(String source, JavaExpressionEvaluator.Context context) throws Exception {
        if (source.length() > 30_000) throw new IllegalArgumentException("Java fragment exceeds 30000 characters");
        VirtualMachine vm = context.vm();
        List<ReferenceType> helpers = vm.classesByName(PausedEvaluationBridge.class.getName());
        if (helpers.size() != 1 || !(helpers.getFirst() instanceof ClassType bridge) || !bridge.isInitialized()) {
            throw new IllegalStateException("Compiled evaluation requires the preloaded TotalDebug evaluation helper");
        }
        String preparedClasspath = this.classpath.get();
        if (preparedClasspath == null || preparedClasspath.isBlank()) {
            throw new IllegalStateException("Compiled evaluation requires the prepared runtime compiler classpath");
        }
        com.sun.jdi.StackFrame frame = context.frame();
        ReferenceType owner = frame.location().declaringType();
        if (vm.classesByName(owner.name()).size() > 1) {
            throw new IllegalArgumentException("Compiled evaluation requires unambiguous classpath bytes for loader-specific type: " + owner.name());
        }
        if (owner.classLoader() == null) {
            throw new IllegalArgumentException("Compiled evaluation requires an application class loader: " + owner.name());
        }
        List<LocalVariable> locals;
        try {
            locals = frame.visibleVariables();
        } catch (AbsentInformationException missing) {
            throw new IllegalArgumentException("Compiled evaluation requires local-variable metadata for "
                    + frame.location().method().name(), missing);
        }
        Map<LocalVariable, Value> initial = frame.getValues(locals);
        String className = "TDFragment" + UUID.randomUUID().toString().replace("-", "");
        String binaryName = "com.github.minecraft_ta.totaldebug.generated." + className;
        StringBuilder fields = new StringBuilder("public boolean __tdPrimitive;\n");
        StringBuilder declarations = new StringBuilder();
        StringBuilder writes = new StringBuilder();
        Set<String> localNames = new HashSet<>();
        for (int i = 0; i < locals.size(); i++) {
            LocalVariable local = locals.get(i);
            String name = this.names.displayedName(owner.name(), frame.location().method().name(),
                    frame.location().method().signature(), local.name());
            if (name.startsWith("__td") || !localNames.add(name)) {
                throw new IllegalArgumentException("Unrepresentable or ambiguous local name: " + name);
            }
            if (vm.classesByName(local.typeName()).size() > 1) {
                throw new IllegalArgumentException("Compiled evaluation requires unambiguous classpath bytes for local type: " + local.typeName());
            }
            String type = sourceType(local.type());
            fields.append("public ").append(type).append(" __tdSlot").append(i).append(";\n");
            declarations.append(type).append(' ').append(name).append(" = this.__tdSlot").append(i).append(";\n");
            writes.append("this.__tdSlot").append(i).append(" = ").append(name).append(";\n");
        }
        boolean hasThis = context.thisObject() != null;
        if (hasThis) fields.append("public ").append(sourceType(owner)).append(" __tdReceiver;\n");
        var parts = com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource.splitImports(source);
        String body = rewrite(parts.body(), localNames, owner, hasThis);
        DebuggerTypeScope scope = this.scopes.scope(owner.name());
        String generated = "package com.github.minecraft_ta.totaldebug.generated;\n"
                + (scope == null ? "" : scope.compilerImports()) + "\n" + parts.imports() + "public class " + className + " {\n" + fields + captureMethods()
                + "public Object run() throws Throwable {\n" + declarations
                + "try { if (Boolean.TRUE.booleanValue()) {\n" + body + "\n} return this;\n"
                + "} finally {\n" + writes + "}\n}\n}";
        Map<String, byte[]> classes = new InMemoryJavaCompiler().compile(generated, binaryName, preparedClasspath);
        DebuggerEvaluationRunner.checkpoint();
        Method install = bridge.concreteMethodByName("install",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Class;");
        if (install == null) throw new IllegalStateException("TotalDebug evaluation helper has an incompatible install method");
        ObjectReference instance = null;
        Throwable failure = null;
        Value result = null;
        boolean invoked = false;
        try {
            ClassObjectReference installed = (ClassObjectReference) bridge.invokeMethod(context.thread(), install,
                    List.of(vm.mirrorOf(encode(classes)), vm.mirrorOf(binaryName), owner.classObject()),
                    ObjectReference.INVOKE_SINGLE_THREADED);
            ClassType compiled = (ClassType) installed.reflectedType();
            instance = compiled.newInstance(context.thread(), compiled.concreteMethodByName("<init>", "()V"),
                    List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
            instance.disableCollection();
            for (int i = 0; i < locals.size(); i++) {
                instance.setValue(compiled.fieldByName("__tdSlot" + i), initial.get(locals.get(i)));
            }
            if (hasThis) instance.setValue(compiled.fieldByName("__tdReceiver"), context.thisObject());
            DebuggerEvaluationRunner.checkpoint();
            invoked = true;
            result = instance.invokeMethod(context.thread(), compiled.concreteMethodByName("run", "()Ljava/lang/Object;"),
                    List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
            if (instance.equals(result)) result = vm.mirrorOfVoid();
            else if (((BooleanValue) instance.getValue(compiled.fieldByName("__tdPrimitive"))).value()) result = unbox(result);
        } catch (Throwable thrown) {
            failure = thrown instanceof InvocationException invocation ? JavaExpressionEvaluator.targetException(invocation) : thrown;
        } finally {
            context.evaluator().refreshStackFrames(context.thread());
            if (instance != null) {
                try {
                    if (invoked) {
                        com.sun.jdi.StackFrame current = context.frame();
                        for (int i = 0; i < locals.size(); i++) {
                            LocalVariable local = locals.get(i);
                            Value updated = instance.getValue(instance.referenceType().fieldByName("__tdSlot" + i));
                            if (!Objects.equals(updated, initial.get(local))) current.setValue(local, updated);
                        }
                    }
                } catch (Throwable writeFailure) {
                    IllegalStateException detail = new IllegalStateException(
                            "Evaluation local writeback failed; earlier writes may have completed", writeFailure);
                    if (failure != null) detail.addSuppressed(failure);
                    failure = detail;
                } finally {
                    try { instance.enableCollection(); }
                    catch (VMDisconnectedException | ObjectCollectedException ignored) { }
                }
            }
        }
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        return result;
    }

    private static String captureMethods() {
        StringBuilder methods = new StringBuilder("private Object __tdCapture(Object value) { return value; }\n");
        for (String type : List.of("boolean", "byte", "short", "char", "int", "long", "float", "double")) {
            methods.append("private Object __tdCapture(").append(type)
                    .append(" value) { this.__tdPrimitive = true; return value; }\n");
        }
        return methods.toString();
    }

    private static Value unbox(Value value) {
        if (value instanceof ObjectReference object && Set.of("java.lang.Boolean", "java.lang.Byte",
                "java.lang.Short", "java.lang.Character", "java.lang.Integer", "java.lang.Long",
                "java.lang.Float", "java.lang.Double").contains(object.referenceType().name())) {
            return object.getValue(object.referenceType().fieldByName("value"));
        }
        return value;
    }

    private static String sourceType(com.sun.jdi.Type type) throws ClassNotLoadedException {
        if (type instanceof com.sun.jdi.ArrayType array) return sourceType(array.componentType()) + "[]";
        if (type instanceof ReferenceType reference && (!reference.isPublic()
                || reference.name().contains("/") || reference.name().matches(".*\\$[0-9].*"))) {
            throw new IllegalArgumentException("Compiled evaluation cannot represent inaccessible or unnamed frame type: "
                    + reference.name());
        }
        return type.name().replace('$', '.');
    }

    private static String rewrite(String source, Set<String> locals, ReferenceType owner, boolean hasThis) {
        ASTNode tree;
        boolean expression;
        try {
            tree = JavaExpressionEvaluator.parse(source);
            expression = true;
        } catch (IllegalArgumentException statements) {
            ASTParser parser = JdtConfiguration.createParser();
            parser.setKind(ASTParser.K_STATEMENTS);
            parser.setSource(source.toCharArray());
            tree = parser.createAST(null);
            expression = false;
        }
        List<Replacement> replacements = new ArrayList<>();
        List<LexicalDeclaration> declared = new ArrayList<>();
        tree.accept(new ASTVisitor() {
            @Override public boolean visit(VariableDeclarationFragment node) {
                declared.add(declarationScope(node));
                return true;
            }
            @Override public boolean visit(SingleVariableDeclaration node) {
                declared.add(declarationScope(node));
                return true;
            }
        });
        tree.accept(new ASTVisitor() {
            @Override public void preVisit(ASTNode node) {
                if (node instanceof SimpleName name && name.getIdentifier().startsWith("__td")) {
                    throw new IllegalArgumentException("Names starting with __td are reserved for evaluation bindings");
                }
                if ((node.getFlags() & (ASTNode.MALFORMED | ASTNode.RECOVERED)) != 0) {
                    throw new IllegalArgumentException("Invalid Java fragment near: " + node);
                }
                if (node instanceof AnonymousClassDeclaration || node instanceof AbstractTypeDeclaration
                        || node instanceof SuperMethodInvocation || node instanceof SuperFieldAccess
                        || node instanceof Pattern || node instanceof PatternInstanceofExpression) {
                    throw new IllegalArgumentException("Compiled frame context does not support " + node.getClass().getSimpleName());
                }
            }
            @Override public boolean visit(ThisExpression node) {
                if (!hasThis || node.getQualifier() != null) throw new IllegalArgumentException("Requested 'this' is unavailable in this frame");
                replacements.add(new Replacement(node.getStartPosition(), node.getLength(), "this.__tdReceiver"));
                return false;
            }
            @Override public boolean visit(MethodInvocation node) {
                if (node.getExpression() == null) {
                    List<Method> methods = owner.methodsByName(node.getName().getIdentifier());
                    if (!methods.isEmpty()) {
                        String qualifier = hasThis ? "this.__tdReceiver" : owner.name().replace('$', '.');
                        replacements.add(new Replacement(node.getStartPosition(), 0, qualifier + "."));
                    }
                }
                return true;
            }
            @Override public boolean visit(SimpleName node) {
                String name = node.getIdentifier();
                if (locals.contains(name) || node.isDeclaration()
                        || declared.stream().anyMatch(declaration -> declaration.contains(node))) return true;
                ASTNode parent = node.getParent();
                if (parent instanceof MethodInvocation call && call.getName() == node
                        || parent instanceof QualifiedName qualified && qualified.getName() == node
                        || parent instanceof FieldAccess field && field.getName() == node
                        || parent instanceof org.eclipse.jdt.core.dom.Type) return true;
                Field field = owner.fieldByName(name);
                if (field != null && (field.isStatic() || hasThis)) {
                    String qualifier = field.isStatic() ? owner.name().replace('$', '.') : "this.__tdReceiver";
                    replacements.add(new Replacement(node.getStartPosition(), node.getLength(), qualifier + "." + name));
                }
                return true;
            }
        });
        StringBuilder rewritten = new StringBuilder(source);
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
        for (Replacement edit : replacements) rewritten.replace(edit.start(), edit.start() + edit.length(), edit.text());
        if (expression) return "return __tdCapture(" + rewritten + ");";
        ASTParser parser = JdtConfiguration.createParser();
        parser.setKind(ASTParser.K_STATEMENTS);
        parser.setSource(rewritten.toString().toCharArray());
        List<Replacement> returns = new ArrayList<>();
        parser.createAST(null).accept(new ASTVisitor() {
            @Override public boolean visit(LambdaExpression node) { return false; }
            @Override public boolean visit(ReturnStatement node) {
                Expression value = node.getExpression();
                String replacement = value == null ? "return this;" : "return __tdCapture("
                        + rewritten.substring(value.getStartPosition(), value.getStartPosition() + value.getLength()) + ");";
                returns.add(new Replacement(node.getStartPosition(), node.getLength(), replacement));
                return false;
            }
        });
        returns.sort(Comparator.comparingInt(Replacement::start).reversed());
        for (Replacement edit : returns) rewritten.replace(edit.start(), edit.start() + edit.length(), edit.text());
        return rewritten.toString();
    }

    private static LexicalDeclaration declarationScope(VariableDeclaration declaration) {
        ASTNode parent = declaration.getParent();
        ASTNode scope;
        int start = declaration.getName().getStartPosition();
        if (parent instanceof LambdaExpression lambda) {
            scope = lambda.getBody();
            start = scope.getStartPosition();
        } else if (parent instanceof EnhancedForStatement loop) {
            scope = loop.getBody();
            // The iterable expression is evaluated outside the loop variable's scope.
            start = scope.getStartPosition();
        } else if (parent instanceof CatchClause clause) {
            scope = clause.getBody();
        } else if (parent instanceof VariableDeclarationExpression expression
                && expression.getParent() instanceof ForStatement loop) {
            scope = loop;
        } else if (parent instanceof VariableDeclarationExpression expression
                && expression.getParent() instanceof TryStatement statement) {
            // Resource names are visible to later resources and the try body, but not catch/finally blocks.
            scope = statement.getBody();
        } else if (parent instanceof VariableDeclarationStatement statement) {
            scope = statement.getParent();
        } else {
            throw new IllegalArgumentException("Compiled frame context does not support declaration scope: "
                    + parent.getClass().getSimpleName());
        }
        return new LexicalDeclaration(declaration.getName().getIdentifier(), start,
                scope.getStartPosition() + scope.getLength());
    }

    private record LexicalDeclaration(String name, int start, int end) {
        boolean contains(SimpleName reference) {
            return this.name.equals(reference.getIdentifier())
                    && reference.getStartPosition() >= this.start && reference.getStartPosition() < this.end;
        }
    }

    private static String encode(Map<String, byte[]> classes) throws java.io.IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(classes.size());
            for (var entry : classes.entrySet()) {
                output.writeUTF(entry.getKey());
                output.writeInt(entry.getValue().length);
                output.write(entry.getValue());
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private record Replacement(int start, int length, String text) { }
}
