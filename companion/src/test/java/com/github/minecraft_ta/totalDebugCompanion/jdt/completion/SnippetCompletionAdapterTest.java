package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SnippetCompletionAdapterTest {
    @Test void concurrentCompletionWorkersDoNotShareMutableMatcherState() throws Exception {
        try (var workers = Executors.newFixedThreadPool(4)) {
            var results = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                boolean snippet = worker % 2 == 0;
                results.add(workers.submit(() -> {
                    for (int i = 0; i < 10_000; i++)
                        assertEquals(snippet, SnippetCompletionAdapter.isSnippet(snippet ? "call(${1:value})${0}" : "plainText"));
                }));
            }
            for (var result : results) result.get();
        }
    }
}
