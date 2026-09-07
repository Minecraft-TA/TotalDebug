package com.github.minecraft_ta.totaldebug.script;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionValueCaptureTest {
    @Test
    void capturesPrivateFieldsAndCyclesWithoutRetainingTheObject() {
        Node first = new Node("first");
        Node second = new Node("second");
        first.next = second;
        second.next = first;

        ExecutionValue snapshot = ExecutionValueCapture.capture(first);

        assertEquals(ExecutionValue.Kind.OBJECT, snapshot.kind());
        assertEquals(2, snapshot.totalChildren());
        ExecutionValue next = child(snapshot, "next");
        assertEquals("second", child(next, "name").value().text());
        ExecutionValue cycle = child(next, "next");
        assertEquals(ExecutionValue.Kind.REFERENCE, cycle.kind());
        assertEquals(snapshot.identity(), cycle.identity());
    }

    @Test
    void boundsLargeRootCollectionsAndReportsTheOmittedValues() {
        List<Integer> values = new ArrayList<>();
        for (int index = 0; index < ExecutionValueCapture.MAX_ROOT_CHILDREN + 10; index++) {
            values.add(index);
        }

        ExecutionValue snapshot = ExecutionValueCapture.capture(values);

        assertEquals(values.size(), snapshot.totalChildren());
        assertEquals(ExecutionValueCapture.MAX_ROOT_CHILDREN, snapshot.children().size());
        assertTrue(snapshot.truncated());
    }

    @Test
    void leavesJdkImplementationInternalsCollapsed() {
        ExecutionValue snapshot = ExecutionValueCapture.capture(new StringBuilder("value"));

        assertEquals(ExecutionValue.Kind.OBJECT, snapshot.kind());
        assertTrue(snapshot.children().isEmpty());
        assertTrue(snapshot.preview().text().isBlank());
    }

    @Test
    void doesNotInvokeArbitraryToStringWhileCapturingAValue() {
        ArbitraryPreview value = new ArbitraryPreview();

        ExecutionValue snapshot = ExecutionValueCapture.capture(value);

        assertFalse(value.invoked);
        assertTrue(snapshot.preview().text().isBlank());
    }

    private static ExecutionValue child(ExecutionValue snapshot, String name) {
        return snapshot.children().stream()
                .filter(child -> child.name().text().equals(name))
                .findFirst()
                .orElseThrow()
                .value();
    }

    private static final class Node {
        private final String name;
        private Node next;

        private Node(String name) {
            this.name = name;
        }
    }

    private static final class ArbitraryPreview {
        private boolean invoked;

        @Override
        public String toString() {
            this.invoked = true;
            return "should not be called";
        }
    }
}
