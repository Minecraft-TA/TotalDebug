package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.game.Access;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
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

/**
 * Puts key bindings on keys. While the game is connected it changes the binding itself, like its controls screen, and
 * saves {@code options.txt}; otherwise Companion writes the binding's line in {@code options.txt}, which the game
 * reads when it starts. Writing that file while the game runs would be undone the next time the game saves its
 * options, so a game running without a connection is asked to connect first; {@link GameLocation} decides which. Every
 * change is entered in the change record, also one the game applies after Companion stopped waiting for its answer.
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
    private final GameLocation location;
    private final Executor writes;
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<Integer, CompletableFuture<KeyBindingResultPayload>> waiting = new ConcurrentHashMap<>();

    /**
     * Changes the keys of the game {@code location} tells of, in its {@code options.txt}; {@code writes} runs the
     * project's writes, which it finishes before its change record closes.
     */
    public KeyBindingControl(GameLocation location, ChangeRecord record, Executor writes) {
        this.location = Objects.requireNonNull(location, "location");
        this.options = location.workspace().resolve("options.txt");
        this.record = Objects.requireNonNull(record, "record");
        this.writes = Objects.requireNonNull(writes, "writes");
        location.addListener(change -> {
            if (change == GameLocation.Change.DISCONNECTED) gameDisconnected();
        });
    }

    /** The game's {@code options.txt}, where the keys are saved. */
    public Path options() {
        return this.options;
    }

    private void gameDisconnected() {
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

    /**
     * Puts the binding {@code name} on {@code assignment}; {@code shown} is the key it had when the edit was made. Where
     * the key is changed is decided in the project's write queue, after the writes queued before it.
     */
    public CompletableFuture<Result> set(String name, KeyBindings.Assignment shown, KeyBindings.Assignment assignment) {
        CompletableFuture<Decided> decided;
        try {
            decided = CompletableFuture.supplyAsync(() -> switch (this.location.read().client("change keys")) {
                case Access.Live live -> new Decided(null, live.connection());
                case Access.Files ignored -> new Decided(writeOffline(name, shown, assignment), null);
                case Access.Refused refused -> throw new CompletionException(new IOException(refused.reason()));
            }, this.writes);
        } catch (RejectedExecutionException closed) {
            return CompletableFuture.failedFuture(new IOException("The project is closing; the key was not changed"));
        }
        return decided.thenCompose(done -> done.live() == null ? CompletableFuture.completedFuture(done.written())
                : live(done.live(), name, shown, assignment));
    }

    /** What the write queue decided: the key written into options.txt, or the connection to change it live. */
    private record Decided(Result written, GameLocation.Connection live) {
    }

    /** Asks the connected game to change the key, and records the change whenever it answers. */
    private CompletableFuture<Result> live(GameLocation.Connection connection, String name, KeyBindings.Assignment shown,
                                           KeyBindings.Assignment assignment) {
        int id = this.requests.incrementAndGet();
        CompletableFuture<KeyBindingResultPayload> answer = new CompletableFuture<>();
        this.waiting.put(id, answer);
        // The change is recorded when the game answers, however late; the caller only waits a while for it.
        CompletableFuture<Result> applied = answer.thenApply(payload -> {
            if (!payload.error().isEmpty()) throw new CompletionException(new IOException(payload.error()));
            return recorded(name, shown, new Result(new KeyBindings.Assignment(payload.previousKey(), payload.previousModifier()),
                    new KeyBindings.Assignment(payload.key(), payload.modifier()), true));
        });
        if (!connection.send(new SetKeyBindingMessage(id, name, assignment.key(), assignment.modifier()))) {
            this.waiting.remove(id);
            return CompletableFuture.failedFuture(new IOException("The game is not connected"));
        }
        CompletableFuture<Result> waited = applied.copy();
        CompletableFuture.delayedExecutor(ANSWER_SECONDS, TimeUnit.SECONDS).execute(() -> waited.completeExceptionally(
                new IOException("The game has not answered yet; the change is recorded if the game applies it")));
        return waited;
    }

    /** Writes the binding into {@code options.txt}, which only a closed game reads again. In the write queue. */
    private Result writeOffline(String name, KeyBindings.Assignment shown, KeyBindings.Assignment assignment) {
        try {
            return recorded(name, shown, new Result(writeOptions(name, assignment), assignment, false));
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private Result recorded(String name, KeyBindings.Assignment shown, Result done) {
        this.record.changed(new ChangeRecord.KeyBinding(name), (done.previous() == null ? shown : done.previous()).encode(),
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
