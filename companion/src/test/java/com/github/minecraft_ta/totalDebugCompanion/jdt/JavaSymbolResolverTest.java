package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JavaSymbolResolverTest {
    private static final String SOURCE = """
            package example;

            import java.util.List;

            final class Target {
                List<String> imported;
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

                static boolean callsExternalMethod() {
                    return "value".isBlank();
                }
            }
            """;

    @BeforeAll
    static void initializeClassIndex() throws IOException {
        CompanionClassIndex.set(ClassIndex.fromBytes(List.of(
                classBytes(Object.class),
                classBytes(String.class),
                classBytes(List.class)
        )));
    }

    @AfterAll
    static void closeClassIndex() {
        ASTCache.removeFromCache("symbols");
        ASTCache.removeFromCache("constructor");
        ASTCache.removeFromCache("local");
        ASTCache.removeFromCache("navigation");
        CompanionClassIndex.get().close();
        CompanionClassIndex.clear();
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
    void resolvesAnExternalIndexedMethodToItsRuntimeOwner() throws Exception {
        String key = prepareAst("external-method");

        var method = JavaSymbolResolver.resolve(key, SOURCE.indexOf("isBlank"));

        assertEquals(
                new CodeSymbol.MethodSymbol("java.lang.String", "isBlank", "()Z"),
                method.symbol()
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
    void resolvesTheConcreteImportedTypeFromAPackageSegment() throws Exception {
        String key = prepareAst("navigation");

        String owner = JavaSymbolResolver.navigationOwnerClass(key, SOURCE.indexOf("java.util"));

        assertEquals("java.util.List", owner);
    }

    private static String prepareAst(String key) throws InterruptedException {
        CountDownLatch parsed = new CountDownLatch(1);
        ASTCache.addChangeListener(key, (unit, version) -> parsed.countDown());
        ASTCache.update(key, "Target", SOURCE);
        assertTrue(parsed.await(5, TimeUnit.SECONDS), "Timed out waiting for the Java model");
        return key;
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = Objects.requireNonNull(type.getResourceAsStream(resource), resource)) {
            return stream.readAllBytes();
        }
    }
}
