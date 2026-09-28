package com.github.minecraft_ta.totaldebug.evaluation;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review probes compare the preflight result with actual execution of compiler-produced bytecode. */
class ScriptMemberLinkProbeTest {
    @TempDir
    Path temporary;

    private record Case(String name, String client, String server, String body, boolean links) {}
    private record Outcome(List<String> unresolved, Object value, Throwable failure) {}

    @TestFactory
    Stream<DynamicTest> changedMemberMatrix() {
        return Stream.of(
                new Case("matching static method", "public static int pick() { return 1; }",
                        "public static int pick() { return 7; }", "return Api.pick();", true),
                new Case("missing static method", "public static int pick() { return 1; }", "",
                        "return Api.pick();", false),
                new Case("changed method return descriptor", "public static int pick() { return 1; }",
                        "public static long pick() { return 7; }", "return Api.pick();", false),
                new Case("changed overload", "public static int pick(int a) { return 1; }",
                        "public static int pick(long a) { return 7; }", "return Api.pick(1);", false),
                new Case("static method became instance", "public static int pick() { return 1; }",
                        "public int pick() { return 7; }", "return Api.pick();", false),
                new Case("instance method became static", "public int pick() { return 1; }",
                        "public static int pick() { return 7; }", "return new Api().pick();", false),
                new Case("matching static field", "public static int value = 1;",
                        "public static int value = 7;", "return Api.value;", true),
                new Case("missing field", "public static int value = 1;", "", "return Api.value;", false),
                new Case("changed field descriptor", "public static int value = 1;",
                        "public static long value = 7;", "return Api.value;", false),
                new Case("static field became instance", "public static int value = 1;",
                        "public int value = 7;", "return Api.value;", false),
                new Case("instance field became static", "public int value = 1;",
                        "public static int value = 7;", "return new Api().value;", false),
                new Case("static field write became final", "public static int value = 1;",
                        "public static final int value = Integer.parseInt(\"7\");", "Api.value = 7; return 7;", false),
                new Case("instance field write became final", "public int value = 1;",
                        "public final int value = 7;", "new Api().value = 7; return 7;", false),
                new Case("constructor still available", "public Api(int value) {}",
                        "public Api(int value) {}", "new Api(1); return 7;", true),
                new Case("constructor overload removed", "public Api(int value) {}",
                        "public Api(long value) {}", "new Api(1); return 7;", false),
                new Case("inherited default remains", "public interface Parent { default int pick() { return 1; } } public static class Child implements Parent {}",
                        "public interface Parent { default int pick() { return 7; } } public static class Child implements Parent {}",
                        "return new Api.Child().pick();", true),
                new Case("inherited default removed", "public interface Parent { default int pick() { return 1; } } public static class Child implements Parent {}",
                        "public interface Parent {} public static class Child implements Parent {}",
                        "return new Api.Child().pick();", false)
        ).map(test -> DynamicTest.dynamicTest(test.name(), () -> {
            Outcome outcome = probe(test.client(), test.server(), script(test.body()), List.of());
            assertEquals(test.links(), outcome.unresolved().isEmpty(), () -> test.name() + ": " + outcome);
            if (test.links()) {
                assertNull(outcome.failure(), () -> test.name() + ": " + outcome);
                assertEquals(7, outcome.value());
            } else {
                assertNotNull(outcome.failure(), () -> test.name() + ": " + outcome);
                assertTrue(isLinkFailure(outcome.failure()), () -> test.name() + ": " + outcome.failure());
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> superConstructorMatrix() {
        return Stream.of(
                new Case("public superclass constructor", "public Api(int a) {}", "public Api(int a) {}", "", true),
                new Case("protected superclass constructor", "protected Api(int a) {}", "protected Api(int a) {}", "", true),
                new Case("missing superclass constructor", "protected Api(int a) {}", "protected Api(long a) {}", "", false),
                new Case("private superclass constructor", "protected Api(int a) {}", "private Api(int a) {}", "", false)
        ).map(test -> DynamicTest.dynamicTest(test.name(), () -> {
            Outcome outcome = probe(test.client(), test.server(), subclass(), List.of());
            assertEquals(test.links(), outcome.unresolved().isEmpty(), outcome.toString());
            if (test.links()) {
                assertNull(outcome.failure(), outcome.toString());
                assertEquals(7, outcome.value());
            } else {
                assertNotNull(outcome.failure(), outcome.toString());
                assertTrue(isLinkFailure(outcome.failure()), outcome.toString());
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> implementedByTheScriptMatrix() {
        String functional = "public interface Fn { int call(); } public static int run(Fn f) { return f.call(); }";
        String shape = "public static abstract class Shape { public abstract int area(int x); public int twice(int x) { return 2 * area(x); } } ";
        String square = "package probe; import fixture.Api; public class Probe { static final class Square extends Api.Shape { "
                + "public int area(int x) { return x * x; } } public static int run() { return new Square().twice(2) == 8 ? 7 : 0; } }";
        return Stream.of(
                new Case("lambda for an unchanged interface", functional, functional, "Api.Fn f = () -> 7; return Api.run(f);", true),
                new Case("lambda whose interface method was renamed", functional,
                        "public interface Fn { int invoke(); } public static int run(Fn f) { return f.invoke(); }",
                        "Api.Fn f = () -> 7; return Api.run(f);", false),
                new Case("lambda whose interface became a class", functional,
                        "public static abstract class Fn { public abstract int call(); } public static int run(Fn f) { return f.call(); }",
                        "Api.Fn f = () -> 7; return Api.run(f);", false),
                new Case("override of an unchanged abstract method", shape, shape, square, true),
                new Case("override whose abstract method changed signature", shape,
                        "public static abstract class Shape { public abstract long area(long x); public int twice(int x) { return (int) (2 * area(x)); } } ",
                        square, false)
        ).map(test -> DynamicTest.dynamicTest(test.name(), () -> {
            String source = test.body().startsWith("package") ? test.body() : script(test.body());
            Outcome outcome = probe(test.client(), test.server(), source, List.of());
            assertEquals(test.links(), outcome.unresolved().isEmpty(), () -> test.name() + ": " + outcome);
            if (test.links()) {
                assertNull(outcome.failure(), () -> test.name() + ": " + outcome);
                assertEquals(7, outcome.value());
            } else {
                assertNotNull(outcome.failure(), () -> test.name() + ": " + outcome);
                assertTrue(isLinkFailure(outcome.failure()), () -> test.name() + ": " + outcome.failure());
            }
        }));
    }

    @Test
    void unrelatedMissingMethodParameterDoesNotPreventAnAvailableMethod() throws Exception {
        String members = "public static class Missing {} public static void unused(Missing x) {} public static int pick() { return 7; }";
        Outcome outcome = probe(members, members, script("return Api.pick();"), List.of("fixture.Api$Missing"));
        assertTrue(outcome.unresolved().isEmpty(), outcome.toString());
        assertNull(outcome.failure(), outcome.toString());
        assertEquals(7, outcome.value());
    }

    @Test
    void missingUnrelatedConstructorTypeHidesTheActuallyMissingSuperConstructor() throws Exception {
        Outcome outcome = probe("protected Api(int a) {}",
                "public static class Missing {} protected Api(long a) {} public Api(Missing x) {}",
                subclass(), List.of("fixture.Api$Missing"));
        assertNotNull(outcome.failure(), outcome.toString());
        assertTrue(isLinkFailure(outcome.failure()), outcome.toString());
        System.out.println("Missing superclass constructor preflight: " + outcome.unresolved());
        outcome.failure().printStackTrace(System.out);
        assertFalse(outcome.unresolved().isEmpty(), "Missing superclass constructor must be refused: " + outcome);
    }

    private Outcome probe(String clientMembers, String serverMembers, String source, List<String> removed) throws Exception {
        Path client = Files.createTempDirectory(this.temporary, "client-");
        Map<String, byte[]> server;
        Map<String, byte[]> bytecode;
        try (var compiler = new InMemoryJavaCompiler()) {
            write(client, compiler.compile(api(clientMembers), "fixture.Api", ""));
            bytecode = compiler.compile(source, "probe.Probe", client.toString());
            server = new HashMap<>(compiler.compile(api(serverMembers), "fixture.Api", ""));
        }
        removed.forEach(server::remove);
        ClassLoader runtime = new ScriptClassLoader(getClass().getClassLoader(), server);
        List<String> unresolved = ScriptReferences.read(bytecode).unresolved(runtime);
        try {
            Class<?> entry = new ScriptClassLoader(runtime, bytecode).loadClass("probe.Probe");
            return new Outcome(unresolved, entry.getMethod("run").invoke(null), null);
        } catch (InvocationTargetException failure) {
            return new Outcome(unresolved, null, failure.getCause());
        } catch (LinkageError failure) {
            return new Outcome(unresolved, null, failure);
        }
    }

    private static String api(String members) {
        return "package fixture; public class Api { " + members + " }";
    }

    private static String script(String body) {
        return "package probe; import fixture.Api; public class Probe { public static int run() { " + body + " } }";
    }

    private static String subclass() {
        return "package probe; import fixture.Api; public class Probe extends Api { public Probe() { super(1); } public static int run() { new Probe(); return 7; } }";
    }

    private static boolean isLinkFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof LinkageError || current instanceof ReflectiveOperationException) return true;
        }
        return false;
    }

    private static void write(Path directory, Map<String, byte[]> definitions) throws IOException {
        for (var definition : definitions.entrySet()) {
            Path target = directory.resolve(definition.getKey().replace('.', '/') + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, definition.getValue());
        }
    }
}
