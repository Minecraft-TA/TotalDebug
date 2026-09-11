package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ASTCacheTest {
    private static ClassIndex index;
    @BeforeAll static void bindIndex() throws Exception {
        try (var bytes = Object.class.getResourceAsStream("Object.class")) {
            index = ClassIndex.fromBytes(List.of(bytes.readAllBytes()));
        }
        CompanionClassIndex.set(index);
    }
    @AfterAll static void closeIndex() { CompanionClassIndex.clear(); index.close(); }

    @Test void identicalEditorKeysHaveIndependentModelsAndListeners() throws Exception {
        var first = new ASTCache();
        var second = new ASTCache();
        var secondUpdates = new AtomicInteger();
        second.addChangeListener("same.java", (unit, version) -> secondUpdates.incrementAndGet());
        first.update("same.java", "Sample", "class Sample { int first; }").get(3, TimeUnit.SECONDS);
        second.update("same.java", "Sample", "class Sample { int second; }").get(3, TimeUnit.SECONDS);
        assertNotSame(first.getFromCache("same.java"), second.getFromCache("same.java"));
        assertTrue(first.getContents("same.java").contains("first"));
        assertTrue(second.getContents("same.java").contains("second"));
        first.clear();
        assertNull(first.getFromCache("same.java"));
        assertNotNull(second.getFromCache("same.java"));
        second.update("same.java", "Sample", "class Sample { int retained; }").get(3, TimeUnit.SECONDS);
        assertEquals(2, secondUpdates.get());
        second.clear();
    }

    @Test void pendingPublicationCannotRestoreAClearedEntry() throws Exception {
        var cache = new ASTCache();
        var pending = cache.update("closed.java", "Sample", "class Sample { int field; }");
        cache.clear();
        pending.get(3, TimeUnit.SECONDS);
        assertNull(cache.getFromCache("closed.java"));
        assertNull(cache.getContents("closed.java"));
    }
}
