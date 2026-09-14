package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.ClassBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.VineflowerDecompiler;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.fixture.NavigationFixture;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerBreakpointResolver;
import com.github.minecraft_ta.totalDebugCompanion.navigation.RuntimeMember;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceDocument;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class SourceDocumentIntegrationTest {
    private static final String OWNER = NavigationFixture.class.getName();

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolvesCompiledStructuresAndPreservesResultsThroughCache(boolean stripDebug) throws Exception {
        Map<String, byte[]> classes = fixtureClasses(stripDebug);
        ClassBytecodeSource bytecode = name -> {
            String normalized = name.replace('.', '/');
            if (normalized.endsWith("/class")) normalized = normalized.substring(0, normalized.length() - 6);
            byte[] bytes = classes.get(normalized);
            if (bytes != null) return bytes;
            try (var input = NavigationFixture.class.getResourceAsStream('/' + normalized + ".class")) {
                return input == null ? null : input.readAllBytes();
            }
        };
        var result = new VineflowerDecompiler().decompile(OWNER, bytecode);
        assertTrue(result.isComplete(), result.diagnostics().toString());
        assertFalse(result.symbols().isEmpty());
        if (stripDebug) assertTrue(result.lineMap().isEmpty());
        var document = new SourceDocument(OWNER, result.source(), result.lineMap(), result.variableNames(), result.symbols());
        var store = DecompiledSourceStore.open(directory, "fixture", "symbols");
        Path path = store.write(document);
        var restored = DecompiledSourceStore.open(directory, "fixture", "symbols").read(OWNER).document();
        assertEquals(document.symbols(), restored.symbols());
        assertArrayEquals(document.lineMap().originalToDisplayed(), restored.lineMap().originalToDisplayed());
        var decompiled = new DecompiledSource(path, document, null);
        assertSame(document, decompiled.debugSource().document());

        int methods = 0;
        int classFallbacks = 0;
        int lambdaConstructs = 0;
        int zeroArgumentLambdas = 0;
        for (var entry : classes.entrySet()) {
            String owner = entry.getKey().replace('/', '.');
            var bytecodeMethods = new java.util.ArrayList<Method>();
            new ClassReader(entry.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    bytecodeMethods.add(new Method(name, descriptor, access));
                    return null;
                }
            }, ClassReader.SKIP_CODE);
            for (var method : bytecodeMethods) {
                methods++;
                var member = new RuntimeMember.Method(owner, method.name, method.desc);
                var destination = document.navigate(member);
                assertEquals(destination, restored.navigate(member), member.toString());
                assertTrue(destination.caret() >= 0 && destination.caret() < result.source().length());
                if (destination.kind() == SourceDocument.Kind.CLASS) classFallbacks++;
                if (method.name.startsWith("lambda$") && destination.kind() == SourceDocument.Kind.CONSTRUCT) {
                    lambdaConstructs++;
                    if (method.desc.startsWith("()")) zeroArgumentLambdas++;
                    assertTrue(document.contents().substring(destination.start(), destination.start() + destination.length()).contains("->"));
                    assertTrue(document.declaration(ReferenceLocation.method(owner, method.name, method.desc)).isEmpty());
                }
                if ((method.access & Opcodes.ACC_BRIDGE) != 0) {
                    assertEquals(SourceDocument.Kind.CLASS, destination.kind(), member.toString());
                }
            }
        }
        assertTrue(methods > 40, "The fixture must exercise its nested and generated methods");
        assertTrue(classFallbacks > 10, "Generated methods must be represented honestly");
        assertTrue(lambdaConstructs >= 4, "Parameterized and nested lambda identities must survive cache reloads");
        assertTrue(zeroArgumentLambdas > 0, "A zero-argument lambda with visible locals should resolve to its block");
        for (String descriptor : List.of("(Ljava/lang/String;)Ljava/lang/String;", "(Ljava/lang/Integer;)Ljava/lang/String;")) {
            var member = new RuntimeMember.Method(OWNER, "overloaded", descriptor);
            var exact = document.navigate(member);
            assertEquals(SourceDocument.Kind.DECLARATION, exact.kind());
            assertTrue(document.contents().startsWith("overloaded(", exact.caret()));
            var location = ReferenceLocation.method(OWNER, "overloaded", descriptor);
            assertEquals(exact, document.declaration(location).orElseThrow());
        }
        var dataOwner = OWNER + "$Data";
        var accessor = document.navigate(new RuntimeMember.Method(dataOwner, "text", "()Ljava/lang/String;"));
        assertEquals(SourceDocument.Kind.CONSTRUCT, accessor.kind());
        assertTrue(document.contents().startsWith("text", accessor.caret()));
        assertTrue(document.declaration(ReferenceLocation.method(dataOwner, "text", "()Ljava/lang/String;")).isEmpty());
        assertTrue(document.declaration(ReferenceLocation.recordComponent(dataOwner, "text", "Ljava/lang/String;")).isPresent());

        var init = ReferenceLocation.method(OWNER, "<clinit>", "()V");
        var staticUse = document.usage(init, ReferenceQuery.stringLiteral("first"));
        assertEquals(SourceDocument.Kind.OCCURRENCE, staticUse.kind());
        assertTrue(document.contents().startsWith("\"first\"", staticUse.caret()));
        assertEquals(staticUse, restored.usage(init, ReferenceQuery.stringLiteral("first")));
        assertTrue(document.symbols().stream().anyMatch(span -> span.role() == SourceDocument.SymbolRole.CONSTANT_FIELD));
        for (var field : List.of(new CodeSymbol.FieldSymbol(OWNER, "mutable", "Ljava/lang/String;"),
                new CodeSymbol.FieldSymbol(OWNER, "numbers", "[I"))) {
            var read = document.usage(init, field.referenceQuery());
            assertEquals(SourceDocument.Kind.OCCURRENCE, read.kind());
            assertTrue(document.contents().startsWith(field.name(), read.caret()));
            assertEquals(read, restored.usage(init, field.referenceQuery()));
        }
        String anonymousOwner = document.symbols().stream().map(SourceDocument.SymbolSpan::symbol)
                .filter(symbol -> symbol instanceof CodeSymbol.FieldSymbol field && field.name().equals("marker"))
                .map(CodeSymbol::ownerClassName).findFirst().orElseThrow();
        assertTrue(document.binaryNames().contains(anonymousOwner));
        var anonymousInit = document.usage(ReferenceLocation.method(anonymousOwner, "<clinit>", "()V"),
                ReferenceQuery.stringLiteral("anon-initializer"));
        var anonymousField = document.navigate(new RuntimeMember.Field(anonymousOwner, "marker"));
        assertEquals(SourceDocument.Kind.OCCURRENCE, anonymousInit.kind());
        assertTrue(anonymousInit.caret() >= anonymousField.start()
                && anonymousInit.caret() < anonymousField.start() + anonymousField.length());
        assertEquals(SourceDocument.Kind.CLASS, document.usage(
                ReferenceLocation.method(OWNER + "$Missing", "<clinit>", "()V"),
                ReferenceQuery.stringLiteral("anon-initializer")).kind());

        var constructor = ReferenceLocation.method(OWNER, "<init>", "()V");
        var instanceField = document.usage(constructor, ReferenceQuery.stringLiteral("field"));
        assertEquals(SourceDocument.Kind.OCCURRENCE, instanceField.kind());
        assertTrue(document.contents().startsWith("\"field\"", instanceField.caret()));
        assertEquals(instanceField, restored.usage(constructor, ReferenceQuery.stringLiteral("field")));
        assertEquals(SourceDocument.Kind.DECLARATION,
                document.usage(ReferenceLocation.method(OWNER, "<init>", "(I)V"), ReferenceQuery.stringLiteral("field")).kind());

        var normal = ReferenceLocation.method(OWNER, "overloaded", "(Ljava/lang/String;)Ljava/lang/String;");
        var trim = document.usage(normal, ReferenceQuery.methodReference("java.lang.String", "trim", "()Ljava/lang/String;"));
        assertEquals(SourceDocument.Kind.OCCURRENCE, trim.kind());
        assertTrue(document.contents().startsWith("trim", trim.caret()));

        var nested = document.navigate(new RuntimeMember.Method(OWNER + "$Nested", "apply", "(Ljava/lang/String;)Ljava/lang/String;"));
        assertEquals(SourceDocument.Kind.DECLARATION, nested.kind());
        assertEquals(OWNER + "$Nested", document.ownerAtLine(document.lineAt(nested.caret())));
        var annotation = document.navigate(new RuntimeMember.Method(OWNER + "$Marker", "type", "()Ljava/lang/Class;"));
        assertEquals(SourceDocument.Kind.DECLARATION, annotation.kind());
        var anonymous = document.navigate(new RuntimeMember.Method(OWNER + "$1", "get", "()Ljava/lang/String;"));
        assertEquals(SourceDocument.Kind.DECLARATION, anonymous.kind());
        assertEquals(OWNER + "$1", document.ownerAtLine(document.lineAt(anonymous.caret())));
        if (!stripDebug) {
            var normalMethod = document.navigate(new RuntimeMember.Method(OWNER, "overloaded", "(Ljava/lang/String;)Ljava/lang/String;"));
            var breakpoint = DebuggerBreakpointResolver.resolve(decompiled.debugSource(), document.lineAt(normalMethod.caret()), null, null).orElseThrow();
            assertTrue(breakpoint.isMethodEntry());
            assertEquals("overloaded", breakpoint.method().name());
        }
        System.out.printf("Source navigation fixture: debug=%s methods=%d classFallbacks=%d symbols=%d%n",
                !stripDebug, methods, classFallbacks, document.symbols().size());
    }

    private record Method(String name, String desc, int access) {}

    private static Map<String, byte[]> fixtureClasses(boolean stripDebug) throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        var pending = new ArrayDeque<String>();
        String root = OWNER.replace('.', '/');
        pending.add(root);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (classes.containsKey(name)) continue;
            byte[] bytes;
            try (var input = NavigationFixture.class.getResourceAsStream('/' + name + ".class")) {
                bytes = java.util.Objects.requireNonNull(input, name).readAllBytes();
            }
            if (stripDebug) {
                var writer = new ClassWriter(0);
                new ClassReader(bytes).accept(writer, ClassReader.SKIP_DEBUG);
                bytes = writer.toByteArray();
            }
            classes.put(name, bytes);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public void visitInnerClass(String inner, String outer, String simple, int access) {
                    if (inner.startsWith(root + '$')) pending.add(inner);
                }
            }, ClassReader.SKIP_CODE);
        }
        return classes;
    }
}
