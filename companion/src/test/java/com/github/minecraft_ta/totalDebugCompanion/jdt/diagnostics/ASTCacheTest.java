package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ASTCacheTest {
    @Test void independentRegistriesDeliverTheWholeAcceptedResultAndRevocation() {
        var first = new ASTCache();
        var second = new ASTCache();
        var received = new ArrayList<JavaAnalysis>();
        second.addChangeListener("same", received::add);
        var a = first.register("same", () -> {});
        var b = second.register("same", () -> {});
        a.publish(result("first", 1));
        var expected = result("second", 2);
        b.publish(expected);
        assertSame(expected, received.getFirst());
        assertEquals("first", first.getSnapshot("same").contents());
        assertEquals(2, received.getFirst().revision());
        b.publish(null);
        assertNull(second.getSnapshot("same"));
        assertNull(received.getLast());
        assertNotNull(first.getSnapshot("same"));
    }

    @Test void clearedOrClosedOwnerCannotPublishIntoAReusedKey() {
        var cache = new ASTCache();
        var old = cache.register("same", () -> {});
        cache.clear();
        var current = cache.register("same", () -> {});
        var expected = result("new", 0);
        current.publish(expected);
        old.publish(result("old", 100));
        old.close();
        assertSame(expected, cache.getSnapshot("same"));
        current.close();
        assertNull(cache.getSnapshot("same"));
        current.publish(expected);
        assertNull(cache.getSnapshot("same"));
    }

    @Test void environmentRefreshNotifiesOwnersWithoutInventingAnAnalysis() {
        var cache = new ASTCache();
        int[] refreshes = {0};
        cache.register("same", () -> refreshes[0]++);
        cache.refreshEnvironment();
        assertEquals(1, refreshes[0]);
        assertNull(cache.getSnapshot("same"));
    }

    private static JavaAnalysis result(String text, long revision) {
        return new JavaAnalysis(revision, CompanionClassIndex.identity(), null, text, JavaSourceMap.IDENTITY,
                Map.of(), List.of(), List.of(), true, List.of(), List.of());
    }
}
