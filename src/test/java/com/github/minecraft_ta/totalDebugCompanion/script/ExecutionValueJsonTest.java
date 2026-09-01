package com.github.minecraft_ta.totalDebugCompanion.script;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionValueJsonTest {
    @Test
    void convertsCompleteOrdinaryValuesToNaturalJsonData() {
        ExecutionValue list = composite(
                ExecutionValue.Kind.COLLECTION,
                List.of(
                        child("0", number("1")),
                        child("1", number("9007199254740993"))
                )
        );
        ExecutionValue map = composite(
                ExecutionValue.Kind.MAP,
                List.of(new ExecutionValue.Child(
                        text(""),
                        ExecutionValue.ChildKind.MAP_ENTRY,
                        scalar(ExecutionValue.Kind.STRING, "values"),
                        list
                ))
        );

        assertEquals(
                Map.of("values", List.of(BigInteger.ONE, new BigInteger("9007199254740993"))),
                map.toJsonValue()
        );
    }

    @Test
    void retainsMetadataForTruncatedScalarsAndNonStringMapKeys() {
        ExecutionValue truncated = new ExecutionValue(
                text("java.lang.String"),
                new ExecutionText("prefix", 100, true),
                text(""),
                ExecutionValue.Kind.STRING,
                0,
                0,
                false,
                List.of()
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> scalar = (Map<String, Object>) truncated.toJsonValue();
        assertEquals("prefix", scalar.get("value"));
        assertEquals(100, scalar.get("value_total_characters"));

        ExecutionValue map = composite(
                ExecutionValue.Kind.MAP,
                List.of(new ExecutionValue.Child(
                        text(""),
                        ExecutionValue.ChildKind.MAP_ENTRY,
                        number("42"),
                        scalar(ExecutionValue.Kind.STRING, "answer")
                ))
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) map.toJsonValue();
        assertTrue(envelope.containsKey("entries"));
    }

    @Test
    void keepsIdentityMetadataWhenANaturalContainerIsReferenced() {
        ExecutionValue reference = new ExecutionValue(
                text("java.util.List"),
                text("List"),
                text("reference #1"),
                ExecutionValue.Kind.REFERENCE,
                1,
                0,
                false,
                List.of()
        );
        ExecutionValue list = new ExecutionValue(
                text("java.util.List"),
                text("size = 1"),
                text(""),
                ExecutionValue.Kind.COLLECTION,
                1,
                1,
                false,
                List.of(child("0", reference))
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) list.toJsonValue();
        assertEquals(1, envelope.get("identity"));
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedReference = (Map<String, Object>) ((List<?>) envelope.get("values")).getFirst();
        assertEquals(1, nestedReference.get("reference"));
    }

    private static ExecutionValue number(String value) {
        return scalar(ExecutionValue.Kind.NUMBER, value);
    }

    private static ExecutionValue scalar(ExecutionValue.Kind kind, String value) {
        return new ExecutionValue(text("type"), text(value), text(""), kind, 0, 0, false, List.of());
    }

    private static ExecutionValue composite(
            ExecutionValue.Kind kind,
            List<ExecutionValue.Child> children
    ) {
        return new ExecutionValue(text("type"), text(""), text(""), kind, 1, children.size(), false, children);
    }

    private static ExecutionValue.Child child(String name, ExecutionValue value) {
        return new ExecutionValue.Child(
                text(name),
                ExecutionValue.ChildKind.COLLECTION_ELEMENT,
                null,
                value
        );
    }

    private static ExecutionText text(String value) {
        return new ExecutionText(value, value.length(), false);
    }
}
