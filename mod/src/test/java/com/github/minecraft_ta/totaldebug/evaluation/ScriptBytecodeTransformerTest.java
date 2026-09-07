package com.github.minecraft_ta.totaldebug.evaluation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compiles, transforms, verifies and executes scripts against a separate runtime classloader. */
class ScriptBytecodeTransformerTest {
    @TempDir
    static Path runtimeDirectory;
    private static Map<String, byte[]> runtimeClasses;

    @BeforeAll
    static void compileRuntimeFixtures() throws Exception {
        runtimeClasses = compileRaw("audit.runtime.Types", """
                package audit.runtime;
                public class Types {
                    public static class Base { public String tag() { return "base"; } }
                    public static class Left extends Base { public String tag() { return "left"; } }
                    public static class Right extends Base { public String tag() { return "right"; } }
                    public interface Tagged { String tag(); }
                    public static class TaggedLeft implements Tagged { public String tag() { return "left"; } }
                    public static class TaggedRight implements Tagged { public String tag() { return "right"; } }
                    public static class Node {
                        public final String text;
                        public Node(String text) { this.text = text; }
                        public Node(Node child) { this.text = "(" + child.text + ")"; }
                        public Node(Node left, Node right) { this.text = left.text + ":" + right.text; }
                        public Node(long number, double decimal, Node child) {
                            this.text = number + ":" + decimal + ":" + child.text;
                        }
                        public String toString() { return this.text; }
                    }
                    public static class Wrapper {
                        public final Node child;
                        public Wrapper(Node child) { this.child = child; }
                    }
                    public static class VarargsBox {
                        public final String text;
                        public VarargsBox(String prefix, Node... nodes) {
                            this.text = prefix + java.util.Arrays.toString(nodes);
                        }
                    }
                    public static class PrimitiveBox {
                        public final String text;
                        public PrimitiveBox(boolean z, byte b, char c, short s, int i, long l, float f, double d) {
                            this.text = z + ":" + b + ":" + c + ":" + s + ":" + i + ":" + l + ":" + f + ":" + d;
                        }
                    }
                    public static class GenericBox<T> {
                        public final T value;
                        public <U extends T> GenericBox(U value) { this.value = value; }
                    }
                    public static class Trace {
                        public static String text = "";
                        public static int mark(int value) { text += value; return value; }
                        public static int argument() { text += "A"; return 1; }
                    }
                    public static class Ordered {
                        public Ordered(int value) { Trace.text += "C" + value; }
                    }
                    public static class Initialized {
                        static { Trace.text += "I"; }
                        public Initialized(int value) { Trace.text += "C"; }
                    }
                    public static class FailedInitialization {
                        static { if (Boolean.parseBoolean("true")) throw new IllegalStateException("init"); }
                        public FailedInitialization(int value) { }
                    }
                    public static class Failure extends RuntimeException {
                        public Failure(String message) { super(message); }
                    }
                    public static class Throwing {
                        public Throwing(int value) { Trace.text += "C"; throw new Failure("failed"); }
                    }
                    public static class Resource implements AutoCloseable {
                        public Resource() { Trace.text += "O"; }
                        public void close() { Trace.text += "C"; }
                    }
                    public class Inner {
                        public final Node child;
                        public Inner(Node child) { this.child = child; }
                    }
                    public static class PrivateBox implements java.io.Serializable {
                        private int value;
                        private static long count;
                        private PrivateBox(int value) { this.value = value; }
                        private int plus(int amount) { return value + amount; }
                        private static int twice(int value) { return value * 2; }
                        private void set(int value) { this.value = value; }
                        private long wide(long value, double increment) { return value + (long) increment; }
                        private static <T> T identity(T value) { return value; }
                    }
                    public static class PrivateBase {
                        private int field = 11;
                        public PrivateBase() { }
                        private int value() { return 7; }
                    }
                    public static class PrivateChild extends PrivateBase { }
                    public interface DefaultFace { default int value() { return 13; } }
                    public static class ProtectedBase {
                        protected ProtectedBase() { }
                        protected int value() { return 17; }
                    }
                    public static class PrivateConstructorBase {
                        private PrivateConstructorBase() { }
                    }
                    public static class PackageConstructorBase {
                        PackageConstructorBase() { }
                    }
                }
                """, "");
        for (var entry : runtimeClasses.entrySet()) {
            Path file = runtimeDirectory.resolve(entry.getKey().replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        Map<String, byte[]> detached = compileRaw("audit.runtime.DetachedChild", """
                package audit.runtime;
                public class DetachedChild extends Types.PrivateBase { }
                """, runtimeDirectory.toString());
        for (var entry : detached.entrySet()) {
            Files.write(runtimeDirectory.resolve(entry.getKey().replace('.', '/') + ".class"), entry.getValue());
        }
        Map<String, byte[]> allClasses = new LinkedHashMap<>(runtimeClasses);
        allClasses.putAll(detached);
        runtimeClasses = Map.copyOf(allClasses);
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "audit.runtime.Types$Left", false, ScriptBytecodeTransformer.class.getClassLoader()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("constructorCases")
    void preservesConstructorSemantics(Probe probe) throws Throwable {
        verify(probe);
    }

    static Stream<Probe> constructorCases() {
        return Stream.of(
                new Probe("same-type nested construction",
                        "return new Types.Node(new Types.Node(new Types.Node(\"leaf\"))).text;", "((leaf))", "((leaf))"),
                new Probe("same-type sibling construction",
                        "return new Types.Node(new Types.Node(\"left\"), new Types.Node(\"right\")).text;", "left:right", "left:right"),
                new Probe("conditional constructor argument",
                        "return new Types.Node(branch ? new Types.Node(\"left\") : new Types.Node(\"right\")).text;", "(left)", "(right)"),
                same("wide constructor arguments",
                        "return new Types.Node(7L, 2.5, new Types.Node(\"leaf\")).text;", "7:2.5:leaf"),
                same("mixed owners", "return new Types.Wrapper(new Types.Node(new Types.Node(\"x\"))).child.text;", "(x)"),
                same("allocation used only for its side effect", "new Types.Ordered(Types.Trace.mark(1)); return Types.Trace.text;", "1C1"),
                same("left-to-right argument effects", """
                        new Types.Node(new Types.Node("" + Types.Trace.mark(1)), new Types.Node("" + Types.Trace.mark(2)));
                        return Types.Trace.text;
                        """, "12"),
                same("postincrement arguments", "int value = 1; var n = new Types.Node(value++, value++, new Types.Node(\"x\")); return n.text + value;", "1:2.0:x3"),
                new Probe("both branches create the same type", "return (branch ? new Types.Node(\"a\") : new Types.Node(\"b\")).text;", "a", "b"),
                new Probe("null constructor argument", "return new Types.Node(branch ? (String) null : \"x\").text;", null, "x"),
                same("all primitive constructor parameters",
                        "return new Types.PrimitiveBox(true, (byte) 2, 'c', (short) 3, 4, 5L, 6.5f, 7.5).text;",
                        "true:2:c:3:4:5:6.5:7.5"),
                same("varargs constructor", "return new Types.VarargsBox(\"nodes=\", new Types.Node(\"a\"), new Types.Node(\"b\")).text;", "nodes=[a, b]"),
                same("generic constructor", "return new Types.GenericBox<String>(\"value\").value;", "value"),
                same("explicit generic constructor witness", "return new <String>Types.GenericBox<CharSequence>(\"value\").value.toString();", "value"),
                same("constructor array", "return java.util.Arrays.toString(new Types.Node[]{new Types.Node(\"a\"), new Types.Node(\"b\")});", "[a, b]"),
                same("multidimensional constructor array", "return new Types.Node[][]{{new Types.Node(\"a\")}, {new Types.Node(\"b\")}}[1][0].text;", "b"),
                same("repeated loop allocations", "String result = \"\"; for (int i = 0; i < 4; i++) result += new Types.Node(\"\" + i).text; return result;", "0123"),
                same("constructor in loop condition", "int i = 0; while (new Types.Node(\"\" + i++).text.length() == 1 && i < 3) { } return i;", 3),
                same("try-finally", "try { return new Types.Node(\"x\").text; } finally { new Types.Node(\"cleanup\"); }", "x"),
                same("constructor exception", "try { new Types.Throwing(Types.Trace.argument()); return \"bad\"; } catch (Types.Failure expected) { return Types.Trace.text; }", "AC"),
                same("throwing constructor argument", "try { new Types.Wrapper(fail()); return \"bad\"; } catch (Types.Failure expected) { return \"caught\"; }", "caught",
                        "private static Types.Node fail() { throw new Types.Failure(\"arg\"); }"),
                same("try-with-resources", "try (var r = new Types.Resource()) { new Types.Node(\"x\"); } return Types.Trace.text;", "OC"),
                same("monitor around allocation", "synchronized (new Types.Node(\"lock\")) { return new Types.Node(\"value\").text; }", "value"),
                new Probe("switch expression argument", "return new Types.Node(switch (branch ? 1 : 2) { case 1 -> \"a\"; default -> \"b\"; }).text;", "a", "b"),
                new Probe("switch expression allocations", "return (switch (branch ? 1 : 2) { case 1 -> new Types.Node(\"a\"); default -> new Types.Node(\"b\"); }).text;", "a", "b"),
                new Probe("short-circuit allocation", "return (branch && new Types.Node(\"x\").text.length() == 1) ? \"yes\" : \"no\";", "yes", "no"),
                same("lambda body construction", "java.util.function.Supplier<String> make = () -> new Types.Node(\"lambda\").text; return make.get();", "lambda"),
                same("public constructor reference", "java.util.function.Function<String, Types.Node> make = Types.Node::new; return make.apply(\"reference\").text;", "reference"),
                same("anonymous subclass", "return new Types.Node(\"x\") { public String toString() { return text + \"!\"; } }.toString();", "x!"),
                same("local subclass", "class Local extends Types.Node { Local() { super(\"local\"); } } return new Local().text;", "local"),
                same("super argument of the same owner", "return new Child().text;", "(super)",
                        "public static class Child extends Types.Node { Child() { super(new Types.Node(\"super\")); } }"),
                same("delegating this constructor", "return new Child().text;", "(this)",
                        "public static class Child extends Types.Node { Child() { this(new Types.Node(\"this\")); } Child(Types.Node n) { super(n); } }"),
                same("non-static inner allocation", "return new Types().new Inner(new Types.Node(\"inner\")).child.text;", "inner"),
                same("null non-static outer", "Types outer = null; try { outer.new Inner(new Types.Node(\"x\")); return \"bad\"; } catch (NullPointerException expected) { return \"null\"; }", "null"),
                same("allocation inside assertion", "assert new Types.Node(\"\").text.isEmpty(); return \"ok\";", "ok")
        );
    }

    @ParameterizedTest(name = "nested tree seed {0}")
    @MethodSource("nestedTrees")
    void preservesGeneratedNestedConstructorTrees(Probe probe) throws Throwable {
        verify(probe);
    }

    static Stream<Probe> nestedTrees() {
        return IntStream.range(0, 160).mapToObj(seed -> {
            NodeExpression tree = nodeExpression(new Random(seed), 5);
            return same(Integer.toString(seed), "return " + tree.source() + ".text;", tree.value());
        });
    }

    private static NodeExpression nodeExpression(Random random, int depth) {
        if (depth == 0 || random.nextInt(4) == 0) {
            String value = Integer.toString(random.nextInt(100));
            return new NodeExpression("new Types.Node(\"" + value + "\")", value);
        }
        NodeExpression first = nodeExpression(random, depth - 1);
        if (random.nextBoolean()) {
            return new NodeExpression("new Types.Node(" + first.source() + ")", "(" + first.value() + ")");
        }
        NodeExpression second = nodeExpression(random, depth - 1);
        return new NodeExpression("new Types.Node(" + first.source() + "," + second.source() + ")",
                first.value() + ":" + second.value());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mergeCases")
    void preservesFrameMerges(Probe probe) throws Throwable {
        verify(probe);
    }

    static Stream<Probe> mergeCases() {
        return Stream.of(
                new Probe("existing runtime references without constructor rewriting",
                        "return choose(branch, null, null);", "empty", "empty",
                        "private static String choose(boolean branch, Types.Left a, Types.Right b) { Types.Base selected = branch ? a : b; return selected == null ? \"empty\" : selected.tag(); }"),
                new Probe("unrelated method enables class-wide recomputation",
                        "return choose(branch, null, null);", "empty", "empty",
                        "private static void unused() { new Types.Node(\"unused\"); } private static String choose(boolean branch, Types.Left a, Types.Right b) { Types.Base selected = branch ? a : b; return selected == null ? \"empty\" : selected.tag(); }"),
                new Probe("generated merge without external constructor", "Parent value = branch ? new First() : new Second(); return value.tag();", "first", "second", generatedHierarchy()),
                new Probe("runtime array merge", "new Types.Node(\"trigger\"); Types.Base[] values = branch ? new Types.Left[1] : new Types.Right[1]; return values.length;", 1, 1),
                new Probe("runtime multidimensional array merge", "new Types.Node(\"trigger\"); Types.Base[][] values = branch ? new Types.Left[1][1] : new Types.Right[1][1]; return values.length;", 1, 1),
                new Probe("JDK-only hierarchy merge", "Object value = branch ? new java.util.ArrayList<>() : new java.util.LinkedList<>(); new Types.Node(\"trigger\"); return value.getClass().getSimpleName();", "ArrayList", "LinkedList"),
                new Probe("runtime constructor to Object merge", "Object value = branch ? new Types.Left() : new Types.Right(); return value.getClass().getSimpleName();", "Left", "Right"),
                new Probe("runtime interface merge", "new Types.Node(\"trigger\"); Types.Tagged value = branch ? new Types.TaggedLeft() : new Types.TaggedRight(); return value.tag();", "left", "right"),
                new Probe("runtime stack merge", "return (branch ? new Types.Left() : new Types.Right()).tag();", "left", "right"),
                new Probe("runtime and JDK merge", "Object value = branch ? new Types.Left() : new java.util.ArrayList<>(); new Types.Node(\"trigger\"); return value.getClass().getSimpleName();", "Left", "ArrayList"),
                new Probe("runtime arrays with different dimensions", "Object value = branch ? new Types.Left[1][1] : new Types.Right[1]; new Types.Node(\"trigger\"); return java.lang.reflect.Array.getLength(value);", 1, 1),
                new Probe("runtime array and Object merge", "Object value = branch ? new Types.Left[1] : new Object(); new Types.Node(\"trigger\"); return value.getClass().isArray();", true, false),
                new Probe("four-way runtime switch merge", "Types.Base value = switch (branch ? 1 : 2) { case 1 -> new Types.Left(); case 2 -> new Types.Right(); default -> null; }; return value.tag();", "left", "right"),
                new Probe("same runtime type merge", "Types.Node value = branch ? new Types.Node(\"a\") : new Types.Node(\"b\"); return value.text;", "a", "b"),
                new Probe("null and runtime type merge", "Types.Node value = branch ? new Types.Node(\"a\") : null; return value == null ? \"null\" : value.text;", "a", "null")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("initializationCases")
    void preservesClassInitializationOrder(Probe probe) throws Throwable {
        verify(probe);
    }

    static Stream<Probe> initializationCases() {
        return Stream.of(
                same("initialization before constructor arguments",
                        "new Types.Initialized(Types.Trace.argument()); return Types.Trace.text;", "IAC"),
                same("already initialized constructor target",
                        "Class.forName(\"audit.runtime.Types$Initialized\"); new Types.Initialized(Types.Trace.argument()); return Types.Trace.text;", "IAC"),
                same("initialization still happens when an argument throws",
                        "try { new Types.Initialized(failArgument()); } catch (IllegalArgumentException expected) { } return Types.Trace.text;", "IA",
                        "private static int failArgument() { Types.Trace.text += \"A\"; throw new IllegalArgumentException(); }"),
                same("failed initialization does not evaluate arguments",
                        "try { new Types.FailedInitialization(Types.Trace.argument()); } catch (ExceptionInInitializerError expected) { } return Types.Trace.text;", "")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("privateAccessCases")
    void supportsPrivateAccessAfterTransformation(Probe probe) throws Throwable {
        verifyTransformed(probe, source(probe));
    }

    static Stream<Probe> privateAccessCases() {
        return Stream.of(
                same("private constructor and field", "return new Types.PrivateBox(4).value;", 4),
                same("private virtual method", "return new Types.PrivateBox(4).plus(3);", 7),
                same("private static method", "return Types.PrivateBox.twice(4);", 8),
                same("private instance field assignment", "var box = new Types.PrivateBox(4); box.value = 9; return box.value;", 9),
                same("private static wide field assignment", "Types.PrivateBox.count = 19L; return Types.PrivateBox.count;", 19L),
                same("private call inside lambda", "var box = new Types.PrivateBox(4); java.util.function.IntSupplier fn = () -> box.plus(3); return fn.getAsInt();", 7),
                same("private constructor reference", "java.util.function.IntFunction<Types.PrivateBox> make = Types.PrivateBox::new; return make.apply(4).value;", 4),
                same("private bound method reference", "var box = new Types.PrivateBox(4); java.util.function.IntUnaryOperator fn = box::plus; return fn.applyAsInt(3);", 7),
                same("private static method reference", "java.util.function.IntUnaryOperator fn = Types.PrivateBox::twice; return fn.applyAsInt(4);", 8),
                same("private unbound method reference", "java.util.function.ToIntBiFunction<Types.PrivateBox, Integer> fn = Types.PrivateBox::plus; return fn.applyAsInt(new Types.PrivateBox(4), 3);", 7),
                same("private void method reference", "var box = new Types.PrivateBox(1); java.util.function.IntConsumer fn = box::set; fn.accept(9); return box.value;", 9),
                same("private wide method reference", "var box = new Types.PrivateBox(1); java.util.function.ToLongBiFunction<Long, Double> fn = box::wide; return fn.applyAsLong(40L, 2.5);", 42L),
                same("private generic method reference", "java.util.function.Function<String, String> fn = Types.PrivateBox::identity; return fn.apply(\"value\");", "value"),
                same("null bound reference fails at creation", "Types.PrivateBox box = null; try { java.util.function.IntUnaryOperator fn = box::plus; return \"bad\"; } catch (NullPointerException expected) { return \"null\"; }", "null"),
                same("private method inherited through runtime subclass", "return new Types.PrivateChild().value();", 7),
                same("private field inherited through runtime subclass", "return new Types.PrivateChild().field;", 11),
                same("private method inherited outside runtime nest", "return new audit.runtime.DetachedChild().value();", 7),
                same("private field inherited outside runtime nest", "return new audit.runtime.DetachedChild().field;", 11),
                same("private method reference inherited outside runtime nest", "java.util.function.IntSupplier fn = new audit.runtime.DetachedChild()::value; return fn.getAsInt();", 7),
                same("private method inherited through generated subclass", "return new Child().value();", 7,
                        "public static class Child extends Types.PrivateBase { }"),
                same("private field inherited through generated subclass", "return new Child().field;", 11,
                        "public static class Child extends Types.PrivateBase { }"),
                same("private method reference inherited through generated subclass", "java.util.function.IntSupplier fn = new Child()::value; return fn.getAsInt();", 7,
                        "public static class Child extends Types.PrivateBase { }"),
                same("serializable private bound method reference", "var box = new Types.PrivateBox(4); java.util.function.IntUnaryOperator fn = (java.util.function.IntUnaryOperator & java.io.Serializable) box::plus; return ((java.util.function.IntUnaryOperator) roundTrip(fn)).applyAsInt(3);", 7,
                        serializationHelper()),
                same("serializable private static method reference", "java.util.function.IntUnaryOperator fn = (java.util.function.IntUnaryOperator & java.io.Serializable) Types.PrivateBox::twice; return ((java.util.function.IntUnaryOperator) roundTrip(fn)).applyAsInt(4);", 8,
                        serializationHelper()),
                same("serializable reference and captured lambda in one class", """
                        java.util.function.IntUnaryOperator reference = (java.util.function.IntUnaryOperator & java.io.Serializable) Types.PrivateBox::twice;
                        long a = 4L; double b = 2.5; int c = 3; String text = "x";
                        java.util.function.Supplier<String> closure = (java.util.function.Supplier<String> & java.io.Serializable) () -> text + (a + b + c);
                        return ((java.util.function.IntUnaryOperator) roundTrip(reference)).applyAsInt(4) + ":" + ((java.util.function.Supplier<?>) roundTrip(closure)).get();
                        """, "8:x9.5", serializationHelper()),
                same("serialized lambda captures every primitive kind", """
                        java.util.function.IntUnaryOperator reference = (java.util.function.IntUnaryOperator & java.io.Serializable) Types.PrivateBox::twice;
                        boolean z = true; byte b = 2; char c = 'c'; short s = 3; int i = 4; long l = 5; float f = 6.5f; double d = 7.5;
                        java.util.function.Supplier<String> closure = (java.util.function.Supplier<String> & java.io.Serializable)
                                () -> z + ":" + b + ":" + c + ":" + s + ":" + i + ":" + l + ":" + f + ":" + d;
                        return ((java.util.function.IntUnaryOperator) roundTrip(reference)).applyAsInt(4) + ":" + ((java.util.function.Supplier<?>) roundTrip(closure)).get();
                        """, "8:true:2:c:3:4:5:6.5:7.5", serializationHelper()),
                same("serializable reference retains marker interface", "java.util.function.IntUnaryOperator fn = (java.util.function.IntUnaryOperator & java.io.Serializable & Marker) Types.PrivateBox::twice; Object restored = roundTrip(fn); return (restored instanceof Marker) + \":\" + ((java.util.function.IntUnaryOperator) restored).applyAsInt(4);", "true:8",
                        "interface Marker { } " + serializationHelper()),
                same("method references with different receiver capture types", "Types.PrivateBase base = new Types.PrivateBase(); Child child = new Child(); java.util.function.IntSupplier first = base::value; java.util.function.IntSupplier second = child::value; return first.getAsInt() + second.getAsInt();", 14,
                        "public static class Child extends Types.PrivateBase { }"),
                same("private reference inside generated interface", "return Factory.call();", 8,
                        "interface Factory { static int call() { java.util.function.IntUnaryOperator fn = Types.PrivateBox::twice; return fn.applyAsInt(4); } }"),
                same("private super call", "return new Child().call();", 7,
                        "public static class Child extends Types.PrivateBase { int call() { return super.value(); } }")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("additionalLanguageCases")
    void preservesAdditionalLanguageSemantics(Probe probe) throws Throwable {
        verify(probe);
    }

    static Stream<Probe> additionalLanguageCases() {
        return Stream.of(
                same("protected superclass constructor and super call", "return new Child().call();", 17,
                        "public static class Child extends Types.ProtectedBase { Child() { super(); } int call() { return super.value(); } }"),
                same("interface default super call", "return new Child().call();", 13,
                        "public static class Child implements Types.DefaultFace { int call() { return Types.DefaultFace.super.value(); } }"),
                same("interface method reference", "Types.DefaultFace receiver = new Types.DefaultFace() { }; java.util.function.IntSupplier fn = receiver::value; return fn.getAsInt();", 13),
                same("generated record", "return new Value(new Types.Node(\"record\")).toString();", "Value[value=record]",
                        "record Value(Types.Node value) { }"),
                same("array clone", "return new Types.Node[]{new Types.Node(\"value\")}.clone()[0].text;", "value"),
                same("primitive array clone", "return new int[]{4}.clone()[0];", 4),
                same("constructor reference does not initialize at creation", "java.util.function.IntFunction<Types.Initialized> fn = Types.Initialized::new; return Types.Trace.text;", ""),
                same("constructor reference initializes on invocation", "java.util.function.IntFunction<Types.Initialized> fn = Types.Initialized::new; fn.apply(Types.Trace.argument()); return Types.Trace.text;", "AIC"),
                same("serializable public constructor reference", "java.util.function.Function<String, Types.Node> fn = (java.util.function.Function<String, Types.Node> & java.io.Serializable) Types.Node::new; return ((java.util.function.Function<String, Types.Node>) roundTrip(fn)).apply(\"value\").text;", "value",
                        serializationHelper())
        );
    }

    private static String serializationHelper() {
        return """
                private static Object roundTrip(Object value) throws Exception {
                    var bytes = new java.io.ByteArrayOutputStream();
                    try (var output = new java.io.ObjectOutputStream(bytes)) { output.writeObject(value); }
                    try (var input = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())) {
                        @Override protected Class<?> resolveClass(java.io.ObjectStreamClass descriptor)
                                throws java.io.IOException, ClassNotFoundException {
                            return Class.forName(descriptor.getName(), false, ScriptCase.class.getClassLoader());
                        }
                    }) { return input.readObject(); }
                }
                """;
    }

    @Test
    void rejectsPrivateSuperclassConstructor() {
        InMemoryCompilationException exception = assertThrows(
                InMemoryCompilationException.class,
                () -> new InMemoryJavaCompiler().compile(
                        source(same(
                                "private superclass constructor",
                                "return new Child().getClass().getSimpleName();",
                                "Child",
                                "public static class Child extends Types.PrivateConstructorBase { public Child() { super(); } }"
                        )),
                        "audit.scripts.ScriptCase",
                        runtimeDirectory.toString()
                )
        );
        assertTrue(exception.getMessage().contains("cannot call private superclass constructor"));
        assertTrue(exception.getMessage().contains("audit.runtime.Types$PrivateConstructorBase()V"));
    }

    @Test
    void rejectsPackagePrivateSuperclassConstructor() {
        InMemoryCompilationException exception = assertThrows(InMemoryCompilationException.class,
                () -> new InMemoryJavaCompiler().compile(source(same("package constructor", "return null;", null,
                        "public static class Child extends Types.PackageConstructorBase { }")),
                        "audit.scripts.ScriptCase", runtimeDirectory.toString()));
        assertTrue(exception.getMessage().contains("cannot call package-private superclass constructor"));
    }

    @Test
    void mergesRuntimeOnlyTypesAfterConstructorRewriting() throws Throwable {
        verify(new Probe("runtime-only type merge", """
                Types.Base value;
                if (branch) value = new Types.Left();
                else value = new Types.Right();
                return value.tag();
                """, "left", "right"));
    }

    @Test
    void mergesScriptGeneratedTypesAfterConstructorRewriting() throws Throwable {
        verify(new Probe("generated type merge", """
                new Types.Node("trigger frame recomputation");
                Parent value = branch ? new First() : new Second();
                return value.tag();
                """, "first", "second", generatedHierarchy()));
    }

    private static void verify(Probe probe) throws Throwable {
        String name = "audit.scripts.ScriptCase";
        String source = source(probe);
        String classpath = runtimeDirectory.toString();
        Map<String, byte[]> original = compileRaw(name, source, classpath);
        assertEquals(probe.whenTrue(), execute(original, name, true), "untransformed true branch");
        assertEquals(probe.whenFalse(), execute(original, name, false), "untransformed false branch");
        verifyTransformed(probe, source);
    }

    private static String source(Probe probe) {
        return """
                package audit.scripts;
                import audit.runtime.Types;
                public class ScriptCase {
                    public static Object run(boolean branch) throws Throwable {
                        %s
                    }
                    %s
                }
                """.formatted(probe.body(), probe.declarations());
    }

    private static void verifyTransformed(Probe probe, String source) throws Throwable {
        String name = "audit.scripts.ScriptCase";
        Map<String, byte[]> transformed = new InMemoryJavaCompiler().compile(source, name, runtimeDirectory.toString());
        assertEquals(probe.whenTrue(), execute(transformed, name, true), "transformed true branch");
        assertEquals(probe.whenFalse(), execute(transformed, name, false), "transformed false branch");
    }

    private static String generatedHierarchy() {
        return """
                public static class Parent { String tag() { return "parent"; } }
                public static class First extends Parent { String tag() { return "first"; } }
                public static class Second extends Parent { String tag() { return "second"; } }
                """;
    }

    private static Probe same(String name, String body, Object expected) {
        return new Probe(name, body, expected, expected);
    }

    private static Probe same(String name, String body, Object expected, String declarations) {
        return new Probe(name, body, expected, expected, declarations);
    }

    record NodeExpression(String source, String value) { }

    private static Object execute(Map<String, byte[]> classes, String name, boolean branch) throws Throwable {
        ClassLoader runtime = new ScriptClassLoader(ScriptBytecodeTransformerTest.class.getClassLoader(), runtimeClasses);
        ClassLoader script = new ScriptClassLoader(runtime, classes);
        try {
            return script.loadClass(name).getMethod("run", boolean.class).invoke(null, branch);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static Map<String, byte[]> compileRaw(String name, String source, String classpath) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var standard = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
             var memory = new InMemoryJavaFileManager(standard)) {
            var options = List.of("-proc:none", "-g", "-classpath", classpath);
            if (!Boolean.TRUE.equals(compiler.getTask(null, memory, diagnostics, options, null,
                    List.of(new StringInputObject(name, source))).call())) {
                throw new AssertionError("Fixture compilation failed: " + diagnostics.getDiagnostics());
            }
            return memory.bytecode();
        }
    }

    record Probe(String name, String body, Object whenTrue, Object whenFalse, String declarations) {
        Probe(String name, String body, Object whenTrue, Object whenFalse) {
            this(name, body, whenTrue, whenFalse, "");
        }

        @Override
        public String toString() { return this.name; }
    }
}
