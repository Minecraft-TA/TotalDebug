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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Puts key bindings on keys. While the game is connected it changes the binding itself, like its controls screen, and
 * saves {@code options.txt}; otherwise Companion writes the binding's line in {@code options.txt}, which the game
 * reads when it starts. Writing that file while the game runs would be undone the next time the game saves its
 * options. Every change is entered in the change record.
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
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<Integer, CompletableFuture<KeyBindingResultPayload>> waiting = new ConcurrentHashMap<>();
    /** Sends a message to the connected game, or is null while no game is connected. */
    private volatile Predicate<SetKeyBindingMessage> game;

    /** {@code options} is the game's {@code options.txt}. */
    public KeyBindingControl(Path options, ChangeRecord record) {
        this.options = Objects.requireNonNull(options, "options");
        this.record = Objects.requireNonNull(record, "record");
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
        CompletableFuture<Result> result;
        if (send == null) {
            result = CompletableFuture.supplyAsync(() -> {
                try {
                    return new Result(writeOptions(name, assignment), assignment, false);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            });
        } else {
            int id = this.requests.incrementAndGet();
            CompletableFuture<KeyBindingResultPayload> answer = new CompletableFuture<>();
            this.waiting.put(id, answer);
            if (!send.test(new SetKeyBindingMessage(id, name, assignment.key(), assignment.modifier()))) {
                this.waiting.remove(id);
                return CompletableFuture.failedFuture(new IOException("The game is not connected"));
            }
            result = answer.orTimeout(ANSWER_SECONDS, TimeUnit.SECONDS).whenComplete((ignored, failure) -> this.waiting.remove(id))
                    .thenApply(payload -> {
                        if (!payload.error().isEmpty()) throw new CompletionException(new IOException(payload.error()));
                        return new Result(new KeyBindings.Assignment(payload.previousKey(), payload.previousModifier()),
                                new KeyBindings.Assignment(payload.key(), payload.modifier()), true);
                    });
        }
        return result.thenApply(done -> {
            this.record.changed(new ChangeRecord.KeyBinding(name), (done.previous() == null ? shown : done.previous()).encode(),
                    done.current().encode());
            return done;
        });
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
        String original = this.record.original(new ChangeRecord.KeyBinding(name));
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
