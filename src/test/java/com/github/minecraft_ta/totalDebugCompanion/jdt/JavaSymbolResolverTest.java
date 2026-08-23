package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import com.github.minecraft_ta.totalDebugCompanion.search.reference.ReferenceNavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import com.github.tth05.jindex.ClassIndex;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JavaSymbolResolverTest {
    private static final String SOURCE = """
            package example;

            final class Target {
                String field;

                Target(String name) {
                    this.field = name;
                }

                String run(int[] values, String... names) {
                    String local = this.field;
                    return local;
                }

                static Target create() {
                    Target target = new Target("value");
                    target.run(new int[0], "name");
                    return target;
                }
            }
            """;

    @BeforeAll
    static void initializeClassIndex() throws IOException {
        CompanionClassIndex.initialize(ClassIndex.fromBytes(List.of(
                classBytes(Object.class),
                classBytes(String.class)
        )));
    }

    @AfterAll
    static void closeClassIndex() {
        ASTCache.removeFromCache("symbols");
        ASTCache.removeFromCache("constructor");
        ASTCache.removeFromCache("local");
        CompanionClassIndex.close();
    }

    @Test
    void resolvesExactJvmClassFieldAndMethodSymbols() throws Exception {
        String key = prepareAst("symbols");

        var type = JavaSymbolResolver.resolve(key, SOURCE.indexOf("String field"));
        var field = JavaSymbolResolver.resolve(key, SOURCE.indexOf("this.field") + "this.".length());
        var method = JavaSymbolResolver.resolve(key, SOURCE.lastIndexOf("run("));

        assertEquals(new CodeSymbol.ClassSymbol("java.lang.String"), type.symbol());
        assertEquals(
                new CodeSymbol.FieldSymbol("example.Target", "field", "Ljava/lang/String;"),
                field.symbol()
        );
        assertEquals(
                new CodeSymbol.MethodSymbol(
                        "example.Target",
                        "run",
                        "([I[Ljava/lang/String;)Ljava/lang/String;"
                ),
                method.symbol()
        );
    }

    @Test
    void resolvesConstructorToJvmInit() throws Exception {
        String key = prepareAst("constructor");
        int constructorUse = SOURCE.indexOf("new Target") + "new ".length();

        var resolution = JavaSymbolResolver.resolve(key, constructorUse);

        assertEquals(
                new CodeSymbol.MethodSymbol("example.Target", "<init>", "(Ljava/lang/String;)V"),
                resolution.symbol()
        );
    }

    @Test
    void rejectsLocalVariablesWithAnExactReason() throws Exception {
        String key = prepareAst("local");
        int localUse = SOURCE.indexOf("return local") + "return ".length();

        var resolution = JavaSymbolResolver.resolve(key, localUse);

        assertFalse(resolution.isResolved());
        assertTrue(resolution.unavailableReason().contains("Local-variable"));
    }

    @Test
    void usageNavigationIdentifiersMatchTheReceivingJdtBindings() {
        var unit = ASTCache.rawParse("Name", SOURCE);

        assertEquals(
                normalizedBindingIdentifier(unit, "run", false),
                normalizedNavigationIdentifier(ReferenceLocation.method(
                        "example.Target",
                        "run",
                        "([I[Ljava/lang/String;)Ljava/lang/String;"
                ))
        );
        assertEquals(
                normalizedBindingIdentifier(unit, "Target", true),
                normalizedNavigationIdentifier(ReferenceLocation.method(
                        "example.Target",
                        "<init>",
                        "(Ljava/lang/String;)V"
                ))
        );
    }

    private static String prepareAst(String key) throws InterruptedException {
        CountDownLatch parsed = new CountDownLatch(1);
        ASTCache.addChangeListener(key, (unit, version) -> parsed.countDown());
        ASTCache.update(key, "Target", SOURCE);
        assertTrue(parsed.await(5, TimeUnit.SECONDS), "Timed out waiting for the Java model");
        return key;
    }

    private static String normalizedBindingIdentifier(
            org.eclipse.jdt.core.dom.CompilationUnit unit,
            String name,
            boolean constructor
    ) {
        AtomicReference<String> identifier = new AtomicReference<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration declaration) {
                if (declaration.isConstructor() == constructor
                        && declaration.getName().getIdentifier().equals(name)) {
                    identifier.set(CodeUtils.minimalizeMethodIdentifier(
                            declaration.resolveBinding().getKey(),
                            false
                    ).replace("Name~", ""));
                }
                return true;
            }
        });
        return Objects.requireNonNull(identifier.get(), "method binding");
    }

    private static String normalizedNavigationIdentifier(ReferenceLocation location) {
        return CodeUtils.minimalizeMethodIdentifier(
                ReferenceNavigationTarget.from(location).identifier(),
                false
        );
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = Objects.requireNonNull(type.getResourceAsStream(resource), resource)) {
            return stream.readAllBytes();
        }
    }
}
