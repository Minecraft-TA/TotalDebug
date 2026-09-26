package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.KeyBindingResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.SetKeyBindingMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Puts key bindings on keys. While the game is connected it changes the binding itself, like its controls screen, and
 * saves {@code options.txt}; otherwise Companion writes the binding's line in {@code options.txt}, which the game
 * reads when it starts. Writing that file while the game runs would be undone the next time the game saves its
 * options, so a game running without a connection is asked to connect first. Every change is entered in the change
 * record, also one the game applies after Companion stopped waiting for its answer.
 */
public final class KeyBindingControl {
    private static final long ANSWER_SECONDS = 5;

    /** What a change did: the binding's key before and after. */
    public record Result(KeyBindings.Assignment previous, KeyBindings.Assignment current, boolean live) {
    }

    /** A change to make: the binding {@code name}, the key it had when the change was made, and its new key. */
    public record Change(String name, KeyBindings.Assignment shown, KeyBindings.Assignment assignment) {
    }

    private final Path options;
    private final ChangeRecord record;
    private final BooleanSupplier gameRunning;
    private final Executor writes;
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<Integer, CompletableFuture<KeyBindingResultPayload>> waiting = new ConcurrentHashMap<>();
    /** Sends a message to the connected game, or is null while no game is connected. */
    private volatile Predicate<SetKeyBindingMessage> game;

    /**
     * {@code options} is the game's {@code options.txt}; {@code gameRunning} tells whether a game runs there, and
     * {@code writes} runs the project's writes, which it finishes before its change record closes.
     */
    public KeyBindingControl(Path options, ChangeRecord record, BooleanSupplier gameRunning, Executor writes) {
        this.options = Objects.requireNonNull(options, "options");
        this.record = Objects.requireNonNull(record, "record");
        this.gameRunning = Objects.requireNonNull(gameRunning, "gameRunning");
        this.writes = Objects.requireNonNull(writes, "writes");
    }

    /** The game's {@code options.txt}, where the keys are saved. */
    public Path options() {
        return this.options;
    }

    public void gameConnected(Predicate<SetKeyBindingMessage> send) {
        this.game = Objects.requireNonNull(send, "send");
    }

    public void gameDisconnected() {
        this.game = null;
        for (CompletableFuture<KeyBindingResultPayload> request : this.waiting.values()) {
            request.completeExceptionally(new IOException("The game disconnected before it answered"));
        }
        this.waiting.clear();
    }

    /** Takes the game's answer to a request. */
    public void answered(KeyBindingResultPayload result) {
        CompletableFuture<KeyBindingResultPayload> request = this.waiting.remove(result.requestId());
        if (request != null) request.complete(result);
    }

    /** Puts the binding {@code name} on {@code assignment}; {@code shown} is the key it had when the edit was made. */
    public CompletableFuture<Result> set(String name, KeyBindings.Assignment shown, KeyBindings.Assignment assignment) {
        Predicate<SetKeyBindingMessage> send = this.game;
        if (send == null) {
            try {
                return CompletableFuture.supplyAsync(() -> writeOffline(name, shown, assignment), this.writes);
            } catch (RejectedExecutionException closed) {
                return CompletableFuture.failedFuture(new IOException("The project is closing; the key was not changed"));
            }
        }
        int id = this.requests.incrementAndGet();
        CompletableFuture<KeyBindingResultPayload> answer = new CompletableFuture<>();
        this.waiting.put(id, answer);
        // The change is recorded when the game answers, however late; the caller only waits a while for it.
        CompletableFuture<Result> applied = answer.thenApply(payload -> {
            if (!payload.error().isEmpty()) throw new CompletionException(new IOException(payload.error()));
            return recorded(name, shown, new Result(new KeyBindings.Assignment(payload.previousKey(), payload.previousModifier()),
                    new KeyBindings.Assignment(payload.key(), payload.modifier()), true));
        });
        if (!send.test(new SetKeyBindingMessage(id, name, assignment.key(), assignment.modifier()))) {
            this.waiting.remove(id);
            return CompletableFuture.failedFuture(new IOException("The game is not connected"));
        }
        CompletableFuture<Result> waited = applied.copy();
        CompletableFuture.delayedExecutor(ANSWER_SECONDS, TimeUnit.SECONDS).execute(() -> waited.completeExceptionally(
                new IOException("The game has not answered yet; the change is recorded if the game applies it")));
        return waited;
    }

    /** Writes the binding into {@code options.txt}, which only a closed game reads again. */
    private Result writeOffline(String name, KeyBindings.Assignment shown, KeyBindings.Assignment assignment) {
        if (this.gameRunning.getAsBoolean()) {
            throw new CompletionException(new IOException(
                    "The game is running but not connected to Companion; connect it to change keys"));
        }
        try {
            return recorded(name, shown, new Result(writeOptions(name, assignment), assignment, false));
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private Result recorded(String name, KeyBindings.Assignment shown, Result done) {
        this.record.changed(new ChangeRecord.KeyBinding(name), ChangeRecord.Level.PACK, (done.previous() == null ? shown : done.previous()).encode(),
                done.current().encode());
        return done;
    }

    /**
     * Makes several changes at once. Completes, never exceptionally, once all are done, with why each binding that
     * kept its key did so, by name.
     */
    public CompletableFuture<Map<String, String>> setAll(List<Change> changes) {
        Map<String, CompletableFuture<Result>> requests = new LinkedHashMap<>();
        for (Change change : changes) requests.put(change.name(), set(change.name(), change.shown(), change.assignment()));
        return CompletableFuture.allOf(requests.values().toArray(CompletableFuture[]::new)).handle((ignored, failure) -> {
            Map<String, String> failed = new LinkedHashMap<>();
            requests.forEach((name, request) -> {
                if (request.isCompletedExceptionally()) failed.put(name, request.exceptionNow().getMessage());
            });
            return failed;
        });
    }

    /** The value a binding had before Companion first changed it, or null when Companion did not change it. */
    public KeyBindings.Assignment original(String name) {
        String original = this.record.original(new ChangeRecord.KeyBinding(name), ChangeRecord.Level.PACK);
        return original == null ? null : KeyBindings.Assignment.decode(original);
    }

    /**
     * Writes the binding's line of {@code options.txt} and returns the key it had, or null when it had no line. One
     * write at a time, so changes made together all stay in the file.
     */
    private synchronized KeyBindings.Assignment writeOptions(String name, KeyBindings.Assignment assignment) throws IOException {
        String prefix = "key_" + name + ":";
        List<String> lines = Files.isRegularFile(this.options)
                ? new ArrayList<>(Files.readAllLines(this.options, StandardCharsets.UTF_8)) : new ArrayList<>();
        KeyBindings.Assignment previous = null;
        boolean written = false;
        for (int index = 0; index < lines.size(); index++) {
            if (!lines.get(index).startsWith(prefix)) continue;
            previous = KeyBindings.Assignment.decode(lines.get(index).substring(prefix.length()));
            lines.set(index, prefix + assignment.encode());
            written = true;
        }
        if (!written) lines.add(prefix + assignment.encode());
        Files.write(this.options, lines, StandardCharsets.UTF_8);
        return previous;
    }
}
