package com.github.minecraft_ta.totaldebug.evaluation;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ordinary Java 21 programs run, and the link check lets them. */
class ScriptLinkCheckValidProgramsTest {
    @TempDir Path directory;

    private record Probe(String name, String members, String body, Object expected) {}

    @TestFactory
    Stream<DynamicTest> validJavaProgramsLink() {
        return Stream.of(
                new Probe("lambda and bound method reference", "", """
                        ToIntFunction<String> length = String::length;
                        String prefix = "abcd";
                        IntSupplier bound = prefix::length;
                        IntUnaryOperator doubled = value -> value * 2;
                        return doubled.applyAsInt(length.applyAsInt("abc")) + bound.getAsInt();
                        """, 10),
                new Probe("constructor and generic method references", "", """
                        Supplier<ArrayList<String>> make = ArrayList::new;
                        ArrayList<String> values = make.get();
                        Consumer<String> add = values::add;
                        add.accept("abc");
                        return values.stream().map(String::length).reduce(0, Integer::sum);
                        """, 3),
                new Probe("array constructor and primitive clone", "", """
                        IntFunction<int[]> make = int[]::new;
                        int[] original = make.apply(3);
                        original[0] = 7;
                        int[] copy = original.clone();
                        copy[0]++;
                        return original[0] + copy[0] + copy.length;
                        """, 18),
                new Probe("reference and multidimensional array clones", "", """
                        String[][] original = new String[][]{{"abc"}, {"de"}};
                        String[][] copy = original.clone();
                        Supplier<String[]> clone = copy[0]::clone;
                        return clone.get()[0].length() + copy[1][0].length();
                        """, 5),
                new Probe("record generated object methods", "record Pair(int left, String right) {}", """
                        Pair a = new Pair(2, "x");
                        Pair b = new Pair(2, "x");
                        return a.equals(b) && a.hashCode() == b.hashCode() && a.toString().contains("left=2") ? a.left() : -1;
                        """, 2),
                new Probe("enum switch", "enum State { FIRST, SECOND }", """
                        return switch (State.valueOf("SECOND")) { case FIRST -> 1; case SECOND -> 2; };
                        """, 2),
                new Probe("sealed record pattern switch", """
                        sealed interface Shape permits Point, Span {}
                        record Point(int x) implements Shape {}
                        record Span(int x, int y) implements Shape {}
                        """, """
                        Shape shape = new Span(3, 4);
                        return switch (shape) { case Point(int x) -> x; case Span(int x, int y) -> x + y; };
                        """, 7),
                new Probe("string switch and concatenation", "", """
                        String text = "value=" + Integer.parseInt("12");
                        return switch (text) { case "value=12" -> 12; default -> -1; };
                        """, 12),
                new Probe("protected superclass method", """
                        static final class Values extends ArrayList<String> {
                            Values() { super(4); }
                            int trim() { super.removeRange(0, 1); return size(); }
                        }
                        """, """
                        Values values = new Values();
                        values.add("one");
                        values.add("two");
                        return values.trim();
                        """, 1),
                new Probe("protected superclass constructor and field", """
                        static final class Input extends FilterInputStream {
                            Input(InputStream input) { super(input); }
                            int first() throws IOException { return super.in.read(); }
                        }
                        """, """
                        return new Input(new ByteArrayInputStream(new byte[]{9})).first();
                        """, 9),
                new Probe("protected class loader constructor", """
                        static final class Loader extends ClassLoader {
                            Loader() { super((ClassLoader) null); }
                        }
                        """, "return new Loader().getParent() == null ? 1 : 0;", 1),
                new Probe("this and super constructor chaining", """
                        static class Base { final int value; Base(int value) { this.value = value; } }
                        static final class Child extends Base {
                            Child() { this(7); }
                            Child(int value) { super(value); }
                        }
                        """, "return new Child().value;", 7),
                new Probe("interface super and private default helper", """
                        interface Value {
                            private int base() { return 3; }
                            default int value() { return base(); }
                        }
                        static final class Child implements Value {
                            public int value() { return Value.super.value() + 4; }
                        }
                        """, "return new Child().value();", 7),
                new Probe("reflection and caller-sensitive methods", "", """
                        Class<?> type = Class.forName("java.lang.String");
                        Supplier<java.lang.invoke.MethodHandles.Lookup> lookup = java.lang.invoke.MethodHandles::lookup;
                        return (Integer) type.getMethod("length").invoke("abcd") + (lookup.get() == null ? 1 : 0);
                        """, 4),
                new Probe("anonymous class and captured local", "", """
                        int captured = 5;
                        IntSupplier supplier = new IntSupplier() { public int getAsInt() { return captured + 2; } };
                        return supplier.getAsInt();
                        """, 7),
                new Probe("try with resources and multi catch", "", """
                        try (ByteArrayInputStream stream = new ByteArrayInputStream(new byte[]{4, 5})) {
                            return stream.read() + stream.read();
                        } catch (IOException | IllegalArgumentException failure) {
                            return -1;
                        }
                        """, 9),
                new Probe("intersection serializable lambda", "", """
                        IntSupplier supplier = (IntSupplier & Serializable) () -> 8;
                        return supplier.getAsInt();
                        """, 8),
                new Probe("generic bridge method", """
                        interface Value<T> { T value(); }
                        static final class StringValue implements Value<String> {
                            public String value() { return "abc"; }
                        }
                        """, "Value<?> value = new StringValue(); return value.value().toString().length();", 3),
                new Probe("super method reference", """
                        static final class Values extends ArrayList<String> {
                            int inheritedSize() { IntSupplier count = super::size; return count.getAsInt(); }
                        }
                        """, "Values values = new Values(); values.add(\"a\"); return values.inheritedSize();", 1),
                new Probe("nested constructor arguments", "", """
                        return new StringBuilder(new StringBuilder("abc").append("d").toString()).length();
                        """, 4),
                new Probe("synchronized block and thrown exception", "", """
                        Object lock = new Object();
                        synchronized (lock) {
                            try { throw new IllegalStateException("abc"); }
                            catch (RuntimeException failure) { return failure.getMessage().length(); }
                        }
                        """, 3)
        ).map(probe -> DynamicTest.dynamicTest(probe.name(), () -> check(probe)));
    }

    private void check(Probe probe) throws Exception {
        String source = """
                package review.valid;
                import java.io.*;
                import java.util.*;
                import java.util.function.*;
                public class ValidProbe {
                %s
                    public static Object run() throws Exception {
                %s
                    }
                }
                """.formatted(probe.members(), probe.body());
        LinkCheckProbe.Outcome outcome = LinkCheckProbe.run(this.directory, "", "", List.of(), source, "review.valid.ValidProbe");
        assertEquals(probe.expected(), outcome.value(), () -> "The JVM runs the compiled program: " + outcome);
        assertEquals(List.of(), outcome.unresolved(), "A program that runs passes its link check");
    }
}
