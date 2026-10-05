package com.github.minecraft_ta.totaldebug.evaluation;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The link check against the JVM, for members that changed between the client and the server. */
class ScriptLinkCheckMembersTest {
    @TempDir
    Path temporary;

    private record Case(String name, String client, String server, String body, boolean links) {}

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
                        "return new Api.Child().pick();", false),
                // javac guards each constant of an enum switch with a handler for NoSuchFieldError (#85).
                new Case("enum switch over a constant the server lacks", "public enum Color { RED, GREEN, BLUE }",
                        "public enum Color { RED, GREEN }",
                        "switch (Api.Color.valueOf(\"RED\")) { case RED: return 7; case BLUE: return 0; default: return 1; }", true),
                new Case("enum constant the server lacks, used outside a switch too", "public enum Color { RED, GREEN, BLUE }",
                        "public enum Color { RED, GREEN }",
                        "switch (Api.Color.valueOf(\"RED\")) { case RED: return Api.Color.BLUE == null ? 0 : 7; default: return 1; }", false),
                new Case("missing method the script catches", "public static int pick() { return 1; }", "public static int other() { return 0; }",
                        "try { return Api.pick(); } catch (NoSuchMethodError absent) { return 7; }", true),
                new Case("missing field used after the handler's range", "public static int value = 1;", "public static int other = 0;",
                        "try { Api.value = 2; } catch (NoSuchFieldError absent) { } return Api.value;", false),
                new Case("field that became an instance field, inside a handler for a missing one", "public static int value = 1;",
                        "public int value = 7;", "try { return Api.value; } catch (NoSuchFieldError absent) { return 7; }", false)
        ).map(test -> DynamicTest.dynamicTest(test.name(), () -> {
            LinkCheckProbe.Outcome outcome = probe(test.client(), test.server(), script(test.body()), List.of());
            assertEquals(test.links(), outcome.unresolved().isEmpty(), () -> test.name() + ": " + outcome);
            if (test.links()) {
                assertNull(outcome.failure(), () -> test.name() + ": " + outcome);
                assertEquals(7, outcome.value());
            } else {
                assertNotNull(outcome.failure(), () -> test.name() + ": " + outcome);
                assertTrue(outcome.failedToLink(), () -> test.name() + ": " + outcome.failure());
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
            LinkCheckProbe.Outcome outcome = probe(test.client(), test.server(), subclass(), List.of());
            assertEquals(test.links(), outcome.unresolved().isEmpty(), outcome.toString());
            if (test.links()) {
                assertNull(outcome.failure(), outcome.toString());
                assertEquals(7, outcome.value());
            } else {
                assertNotNull(outcome.failure(), outcome.toString());
                assertTrue(outcome.failedToLink(), outcome.toString());
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
            LinkCheckProbe.Outcome outcome = probe(test.client(), test.server(), source, List.of());
            assertEquals(test.links(), outcome.unresolved().isEmpty(), () -> test.name() + ": " + outcome);
            if (test.links()) {
                assertNull(outcome.failure(), () -> test.name() + ": " + outcome);
                assertEquals(7, outcome.value());
            } else {
                assertNotNull(outcome.failure(), () -> test.name() + ": " + outcome);
                assertTrue(outcome.failedToLink(), () -> test.name() + ": " + outcome.failure());
            }
        }));
    }

    @Test
    void unrelatedMissingMethodParameterDoesNotPreventAnAvailableMethod() throws Exception {
        String members = "public static class Missing {} public static void unused(Missing x) {} public static int pick() { return 7; }";
        LinkCheckProbe.Outcome outcome = probe(members, members, script("return Api.pick();"), List.of("fixture.Api$Missing"));
        assertTrue(outcome.unresolved().isEmpty(), outcome.toString());
        assertNull(outcome.failure(), outcome.toString());
        assertEquals(7, outcome.value());
    }

    @Test
    void aServerClassNamingAMissingTypeDoesNotHideAMissingSuperConstructor() throws Exception {
        LinkCheckProbe.Outcome outcome = probe("protected Api(int a) {}",
                "public static class Missing {} protected Api(long a) {} public Api(Missing x) {}",
                subclass(), List.of("fixture.Api$Missing"));
        assertNotNull(outcome.failure(), outcome.toString());
        assertTrue(outcome.failedToLink(), outcome.toString());
        assertFalse(outcome.unresolved().isEmpty(), "Missing superclass constructor must be refused: " + outcome);
    }

    private LinkCheckProbe.Outcome probe(String clientMembers, String serverMembers, String source, List<String> removed)
            throws Exception {
        return LinkCheckProbe.run(this.temporary, api(clientMembers), api(serverMembers), removed, source, "probe.Probe");
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
}
