package com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.tth05.jindex.ClassIndex;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Token;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class SemanticHighlightingPublicationTest {
    private static final String SOURCE = """
            package example;

            final class Target {
                void doWork() {}

                void invoke() {
                    doWork();
                }
            }
            """;

    @BeforeAll
    static void initializeClassIndex() throws IOException {
        CompanionClassIndex.replace(ClassIndex.fromBytes(List.of(classBytes(Object.class))));
    }

    @AfterAll
    static void closeClassIndex() {
        CompanionClassIndex.close();
    }

    @Test
    void publishesParsedColorsOnlyWhenTheEdtProcessesTheResult() throws Exception {
        String key = newKey();
        try {
            RSyntaxTextArea area = onEdt(() -> editor(key, SOURCE));

            onEdt(() -> {
                assertEquals(Token.IDENTIFIER, methodTokenType(area));

                // Hold this EDT turn until the real AST listener has delivered its result.
                parseAndAwaitDelivery(key, SOURCE);

                assertEquals(Token.IDENTIFIER, methodTokenType(area),
                        "The parsing worker must not change colors before EDT publication");
                return null;
            });

            onEdt(() -> {
                assertEquals(Token.FUNCTION, methodTokenType(area));
                return null;
            });
        } finally {
            ASTCache.removeFromCache(key);
        }
    }

    @Test
    void rejectsAQueuedResultAfterTheDocumentChanges() throws Exception {
        String key = newKey();
        try {
            RSyntaxTextArea area = onEdt(() -> editor(key, SOURCE));
            parseAndAwaitDelivery(key, SOURCE);

            onEdt(() -> {
                assertEquals(Token.FUNCTION, methodTokenType(area));
                parseAndAwaitDelivery(key, SOURCE);

                // The preceding parse is queued behind this EDT turn, so this edit wins.
                area.insert("// added while publication was queued\n", 0);
                assertEquals(Token.FUNCTION, methodTokenType(area));
                return null;
            });

            onEdt(() -> {
                assertEquals(Token.FUNCTION, methodTokenType(area),
                        "A queued parse must not restore semantic offsets from before the edit");
                return null;
            });
        } finally {
            ASTCache.removeFromCache(key);
        }
    }

    @Test
    void rejectsAnOlderCallbackDeliveredAfterANewerParse() throws Exception {
        String key = newKey();
        CountDownLatch oldCallbackEntered = new CountDownLatch(1);
        CountDownLatch releaseOldCallback = new CountDownLatch(1);
        CountDownLatch oldCallbackDelivered = new CountDownLatch(1);
        CountDownLatch newCallbackDelivered = new CountDownLatch(1);
        AtomicBoolean holdFirstCallback = new AtomicBoolean(true);
        AtomicReference<CompilationUnit> oldUnit = new AtomicReference<>();

        // Register before the token maker to pause delivery of the first parsed AST.
        ASTCache.addChangeListener(key, (unit, version) -> {
            if (holdFirstCallback.compareAndSet(true, false)) {
                oldUnit.set(unit);
                oldCallbackEntered.countDown();
                awaitCallbackRelease(releaseOldCallback);
            }
        });

        try {
            RSyntaxTextArea area = onEdt(() -> editor(key, SOURCE));
            ASTCache.addChangeListener(key, (unit, version) -> {
                if (unit == oldUnit.get()) {
                    oldCallbackDelivered.countDown();
                } else {
                    newCallbackDelivered.countDown();
                }
            });

            ASTCache.update(key, "Target", SOURCE);
            await(oldCallbackEntered, "The first AST callback did not reach its gate");

            String currentSource = "// newer source with different token positions\n" + SOURCE;
            onEdt(() -> {
                area.setText(currentSource);
                ASTCache.update(key, "Target", currentSource);
                return null;
            });
            await(newCallbackDelivered, "The newer AST was not delivered");
            onEdt(() -> {
                assertEquals(Token.FUNCTION, methodTokenType(area));
                return null;
            });

            releaseOldCallback.countDown();
            await(oldCallbackDelivered, "The older AST callback did not finish");
            onEdt(() -> {
                assertEquals(Token.FUNCTION, methodTokenType(area),
                        "Late delivery of an older AST must not replace newer semantic colors");
                return null;
            });
        } finally {
            releaseOldCallback.countDown();
            try {
                if (oldCallbackEntered.getCount() == 0) {
                    await(oldCallbackDelivered, "The gated AST callback did not finish during cleanup");
                }
            } finally {
                ASTCache.removeFromCache(key);
            }
        }
    }

    private static RSyntaxTextArea editor(String key, String source) {
        RSyntaxTextArea area = new RSyntaxTextArea();
        CustomJavaTokenMaker tokenMaker = new CustomJavaTokenMaker();
        ((RSyntaxDocument) area.getDocument()).setSyntaxStyle(tokenMaker);
        area.setText(source);
        tokenMaker.setASTKey(key, area);
        return area;
    }

    @Test
    void clearingAProjectPreventsOldCallbacksFromReachingAReusedKey() throws Exception {
        String key = newKey();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ASTCache.addChangeListener(key, (unit, version) -> {
            entered.countDown();
            awaitCallbackRelease(release);
        });
        var old = ASTCache.update(key, "Target", SOURCE);
        try {
            await(entered, "Old parse did not enter its callback");
            ASTCache.clear();
            var received = new java.util.concurrent.CopyOnWriteArrayList<CompilationUnit>();
            ASTCache.addChangeListener(key, (unit, version) -> received.add(unit));
            String next = "// next project\n" + SOURCE;
            ASTCache.update(key, "Target", next).get(10, TimeUnit.SECONDS);
            release.countDown();
            old.get(10, TimeUnit.SECONDS);
            assertEquals(1, received.size());
            assertEquals(next, ASTCache.getContents(key));
            assertEquals(ASTCache.getFromCache(key), received.getFirst());
        } finally {
            release.countDown();
            old.get(10, TimeUnit.SECONDS);
            ASTCache.removeFromCache(key);
        }
    }

    private static void parseAndAwaitDelivery(String key, String source) throws InterruptedException {
        CompilationUnit previous = ASTCache.getFromCache(key);
        CountDownLatch delivered = new CountDownLatch(1);
        Runnable removeListener = ASTCache.addChangeListener(key, (unit, version) -> {
            if (unit != previous) {
                delivered.countDown();
            }
        });
        try {
            ASTCache.update(key, "Target", source);
            await(delivered, "The Java AST was not delivered to the semantic token listener");
        } finally {
            removeListener.run();
        }
    }

    private static int methodTokenType(RSyntaxTextArea area) {
        int offset = area.getText().lastIndexOf("doWork");
        RSyntaxDocument document = (RSyntaxDocument) area.getDocument();
        int line = document.getDefaultRootElement().getElementIndex(offset);
        for (Token token = document.getTokenListForLine(line); token != null; token = token.getNextToken()) {
            if (token.getOffset() == offset && "doWork".equals(token.getLexeme())) {
                return token.getType();
            }
        }
        return fail("No doWork invocation token at " + offset);
    }

    private static String newKey() {
        return "semantic-publication-" + UUID.randomUUID();
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), message);
    }

    private static void awaitCallbackRelease(CountDownLatch latch) {
        try {
            await(latch, "The older AST callback was not released");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("The older AST callback was interrupted", exception);
        }
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = Objects.requireNonNull(type.getResourceAsStream(resource), resource)) {
            return stream.readAllBytes();
        }
    }
}
