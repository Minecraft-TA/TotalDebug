package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.sun.jdi.ObjectReference;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class DebuggerValueStoreTest {
    @Test
    void aliasesAndAnInspectorShareOnePinUntilTheirLastOwnerLeaves() {
        List<Integer> removed = new ArrayList<>();
        DebuggerValueStore values = new DebuggerValueStore(removed::add);
        Target target = new Target();
        values.register(0, "one", target.object, () -> 1);
        values.register(0, "two", target.object, () -> 2);
        var inspector = values.retain(1);
        assertEquals(1, target.disabled);
        values.releaseHistory("one");
        values.releaseHistory("two");
        assertEquals(List.of(2), removed);
        assertEquals(0, target.enabled);
        inspector.close();
        inspector.close();
        assertEquals(List.of(2, 1), removed);
        assertEquals(1, target.enabled);
        assertThrows(IllegalArgumentException.class, () -> values.retain(1));
    }

    @Test
    void aReusedProxyReferenceHasIndependentHistoryOwners() {
        DebuggerValueStore values = new DebuggerValueStore(ignored -> { });
        Target target = new Target();
        values.register(0, "one", target.object, () -> 1);
        values.register(0, "two", target.object, () -> 1);
        values.releaseHistory("one");
        assertEquals(0, target.enabled);
        values.releaseHistory("two");
        assertEquals(1, target.enabled);
        assertEquals(1, target.disabled);
    }

    @Test
    void endingAPauseInvalidatesHistoryAndInspectorsWithoutReleasingNewValues() {
        DebuggerValueStore values = new DebuggerValueStore(ignored -> { });
        Target old = new Target();
        values.register(0, "one", old.object, () -> 1);
        var inspector = values.retain(1);
        values.clear();
        assertEquals(1, old.enabled);
        assertThrows(CancellationException.class, () -> values.register(0, "late", old.object, () -> 2));
        Target replacement = new Target();
        values.register(values.generation(), "new", replacement.object, () -> 1);
        inspector.close();
        values.releaseHistory("one");
        assertEquals(0, replacement.enabled);
        values.clear();
        assertEquals(1, replacement.enabled);
    }

    @Test
    void failedReferencePublicationDoesNotLeaveAPin() {
        DebuggerValueStore values = new DebuggerValueStore(ignored -> { });
        Target target = new Target();
        assertThrows(IllegalStateException.class, () -> values.register(0, "one", target.object,
                () -> { throw new IllegalStateException("publication failed"); }));
        assertEquals(1, target.disabled);
        assertEquals(1, target.enabled);
        values.clear();
        assertEquals(1, target.enabled);
    }

    private static final class Target {
        private int disabled;
        private int enabled;
        private final ObjectReference object = (ObjectReference) Proxy.newProxyInstance(
                ObjectReference.class.getClassLoader(), new Class<?>[]{ObjectReference.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "disableCollection" -> this.disabled++;
                        case "enableCollection" -> this.enabled++;
                        case "hashCode" -> { return System.identityHashCode(proxy); }
                        case "equals" -> { return proxy == args[0]; }
                        case "toString" -> { return "target fixture"; }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                    return null;
                });
    }
}
