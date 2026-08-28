package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerValuePreviewerTest {
    @Test
    void selectsTheRegisteredRendererAndNormalizesItsOutput() {
        AtomicReference<String> expression = new AtomicReference<>();
        DebuggerValuePreviewer previewer = new DebuggerValuePreviewer((source, value, thread) -> {
            expression.set(source);
            return CompletableFuture.completedFuture(string("minecraft:stone\n"));
        });

        DebugEngine.ValuePreview preview = previewer.preview(
                object("net.minecraft.resources.ResourceLocation"), proxy(ThreadReference.class)
        ).join();

        assertEquals("toString()", expression.get());
        assertEquals("minecraft:stone", preview.summary());
        assertEquals("minecraft:stone", preview.detail());
    }

    @Test
    void leavesUnsupportedObjectsUntouched() {
        DebuggerValuePreviewer previewer = new DebuggerValuePreviewer((source, value, thread) -> {
            throw new AssertionError("Unsupported values must not be evaluated");
        });

        DebugEngine.ValuePreview preview = previewer.preview(
                object("example.UnregisteredValue"), proxy(ThreadReference.class)
        ).join();

        assertFalse(preview.available());
    }

    @Test
    void boundsTheInlineSummaryWithoutDiscardingTheFullDetail() {
        String longValue = "x".repeat(200);
        DebuggerValuePreviewer previewer = new DebuggerValuePreviewer((source, value, thread) ->
                CompletableFuture.completedFuture(string(longValue))
        );

        DebugEngine.ValuePreview preview = previewer.preview(
                object("net.minecraft.network.chat.Component"), proxy(ThreadReference.class)
        ).join();

        assertEquals(160, preview.summary().length());
        assertTrue(preview.summary().endsWith("…"));
        assertEquals(longValue, preview.detail());
    }

    private static ObjectReference object(String typeName) {
        ReferenceType type = proxy(ReferenceType.class, "name", typeName);
        return proxy(ObjectReference.class, "referenceType", type);
    }

    private static StringReference string(String value) {
        return proxy(StringReference.class, "value", value);
    }

    private static <T> T proxy(Class<T> type, Object... response) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (ignored, method, args) -> {
            if (response.length == 2 && method.getName().equals(response[0])) {
                return response[1];
            }
            return switch (method.getName()) {
                case "equals" -> ignored == args[0];
                case "hashCode" -> System.identityHashCode(ignored);
                case "toString" -> type.getSimpleName();
                default -> defaultValue(method.getReturnType());
            };
        }));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return 0;
    }
}
