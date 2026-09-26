package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.ConfigValueResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetConfigValuePayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.SetConfigValueMessage;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Configuration values tried in the running game's memory without writing their file. The game applies them as it
 * applies a reloaded file; they last until the game reads the file again, such as after an edit of the file, rejoining
 * a world or a restart. Each is entered in the change record at the Game level, with the file's value as its original.
 */
public final class ConfigGameValues {
    private static final long ANSWER_SECONDS = 5;

    private final ChangeRecord record;
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<Integer, CompletableFuture<ConfigValueResultPayload>> waiting = new ConcurrentHashMap<>();
    private volatile Predicate<SetConfigValueMessage> game;

    public ConfigGameValues(ChangeRecord record) {
        this.record = Objects.requireNonNull(record, "record");
    }

    public void gameConnected(Predicate<SetConfigValueMessage> send) {
        this.game = Objects.requireNonNull(send, "send");
    }

    public void gameDisconnected() {
        this.game = null;
        for (CompletableFuture<ConfigValueResultPayload> request : this.waiting.values()) {
            request.completeExceptionally(new IOException("The game disconnected before it answered"));
        }
        this.waiting.clear();
    }

    /** Takes the game's answer to a request. */
    public void answered(ConfigValueResultPayload result) {
        CompletableFuture<ConfigValueResultPayload> request = this.waiting.remove(result.requestId());
        if (request != null) request.complete(result);
    }

    public boolean connected() {
        return this.game != null;
    }

    /**
     * Sets {@code target} to {@code literal} in the game's memory; {@code fileLiteral} is the value its file holds, which
     * the game uses again once the change ends. Completes with the value the game uses now, as it prints it.
     */
    public CompletableFuture<String> set(ChangeRecord.Setting target, String fileLiteral, String literal) {
        Predicate<SetConfigValueMessage> send = this.game;
        if (send == null) return CompletableFuture.failedFuture(new IOException("Trying a value in the game needs the game running"));
        int id = this.requests.incrementAndGet();
        CompletableFuture<ConfigValueResultPayload> answer = new CompletableFuture<>();
        this.waiting.put(id, answer);
        // The value is recorded when the game answers, however late; the caller only waits a while for it.
        CompletableFuture<String> applied = answer.thenApply(payload -> {
            if (!payload.error().isEmpty()) throw new CompletionException(new IOException(payload.error()));
            this.record.changed(target, ChangeRecord.Level.GAME, fileLiteral, literal);
            return payload.current();
        });
        if (!send.test(new SetConfigValueMessage(new SetConfigValuePayload(id, target.fileName(), target.setting(), literal)))) {
            this.waiting.remove(id);
            return CompletableFuture.failedFuture(new IOException("The game is not connected"));
        }
        CompletableFuture<String> waited = applied.copy();
        CompletableFuture.delayedExecutor(ANSWER_SECONDS, TimeUnit.SECONDS).execute(() -> waited.completeExceptionally(
                new IOException("The game has not answered yet; the value is recorded if the game applies it")));
        return waited;
    }

    /** The value tried in the game for {@code target}, as TOML writes it, or null when none is. */
    public String tried(ChangeRecord.Setting target) {
        ChangeRecord.Change change = this.record.change(target, ChangeRecord.Level.GAME);
        return change == null ? null : change.current();
    }

    /** Puts the file's value back in the game's memory. */
    public CompletableFuture<String> revert(ChangeRecord.Change change) {
        if (!(change.target() instanceof ChangeRecord.Setting target) || change.level() != ChangeRecord.Level.GAME) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Not a setting tried in the game"));
        }
        return set(target, change.original(), change.original());
    }
}
