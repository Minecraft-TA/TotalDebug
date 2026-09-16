package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.fixture.ParameterNamesFixture;
import com.github.tth05.jindex.ClassIndex;

import org.junit.jupiter.api.*;

import java.io.Serializable;
import java.util.*;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class SignatureHelpTest {
    @BeforeAll static void index() throws Exception {
        List<byte[]> classes = new ArrayList<>();
        for (Class<?> type : List.of(Object.class, String.class, Integer.class, Number.class, Map.class, HashMap.class,
                AbstractMap.class, Cloneable.class, Serializable.class, IntBinaryOperator.class,
                ParameterNamesFixture.class, ParameterNamesFixture.Inner.class, ParameterNamesFixture.GenericInner.class)) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) { classes.add(stream.readAllBytes()); }
        }
        CompanionClassIndex.set(ClassIndex.fromBytes(classes));
    }
    @AfterAll static void close() { CompanionClassIndex.get().close(); CompanionClassIndex.clear(); }

    @Test void identifiesTheArgumentAroundStringsNestedCallsAndComments() {
        var help = find("class Proof { void read(String target, int count) {} void run() { read(String.valueOf(12), /* , */ |); } }");
        assertEquals(1, help.argument());
        assertEquals(List.of("String target", "int count"), help.signatures().getFirst().parameters());
        help = find("class Proof { void read(String target, int count) {} void run() { read(\"a,b\", |); } }");
        assertEquals(1, help.argument());
        help = find("class Proof { void read(String target, int count) {} void run() { read(String.valueOf(|), 1); } }");
        assertEquals("valueOf", help.signatures().getFirst().name());
        assertEquals(0, help.argument());
    }

    @Test void ignoresGenericAndLambdaCommas() {
        var help = find("class Proof { void read(Object value, int count) {} void run() { read(new java.util.HashMap<String, Integer>(), |); } }");
        assertEquals(1, help.argument());
        help = find("class Proof { void read(java.util.function.IntBinaryOperator op, int count) {} void run() { read((left, right) -> left + right, |); } }");
        assertEquals(1, help.argument());
    }

    @Test void showsOverloadsPrivateMethodsAndVarargs() {
        var help = find("class Proof { private void read(String text, int... values) {} void read(Object object) {} void run() { read(|); } }");
        assertEquals(2, help.signatures().size());
        assertTrue(help.signatures().stream().anyMatch(signature -> signature.parameters().equals(List.of("String text", "int... values"))));
        help = find("class Proof { void read(String text, int... values) {} void run() { read(\"a\", 1, 2, |); } }");
        assertEquals(3, help.argument());
        assertTrue(help.signatures().getFirst().varargs());
    }

    @Test void resolvesBinaryGenericConstructorNames() {
        var help = find("import " + ParameterNamesFixture.class.getName() + "; class Proof { void run() { new ParameterNamesFixture<String>(|); } }");
        assertEquals(List.of("String initialValue"), help.signatures().getFirst().parameters());
    }

    @Test void rejectsOverloadsIncompatibleWithCompletedArguments() {
        var help = find("class Proof { void read(String text, int count) {} void read(int value, int count) {} void run() { read(\"text\", |); } }");
        assertEquals(1, help.signatures().size());
        assertEquals(List.of("String text", "int count"), help.signatures().getFirst().parameters());
    }

    @Test void handlesUnfinishedCallsAndLeavesClosedCalls() {
        var help = find("class Proof { void read(String text, int count) {} void run() { read(| } }");
        assertNotNull(help);
        assertEquals(0, help.argument());
        assertNull(find("class Proof { void read(String text) {} void run() { read(\"a\")|; } }"));
    }

    private static SignatureHelp find(String markedSource) {
        int caret = markedSource.indexOf('|');
        return SignatureHelp.find("Proof", markedSource.replace("|", ""), caret);
    }
}
