package com.github.minecraft_ta.totalDebugCompanion.change;

import com.github.minecraft_ta.totalDebugCompanion.game.Access;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.ChangePayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ChangeResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessages;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ChangeMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ToServerMessage;
import com.github.tth05.scnet.message.AbstractMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one path of a change Companion makes to values the game keeps (see {@code docs/CHANGE_PIPELINE.md}). A change
 * waits in the project's write queue behind the changes before it; its category decides whether it goes to the connected
 * game or into the file, or is refused; every target must still hold the value the change was made against before any
 * is set; and each value set is entered in the change record. A change the game makes is made whole or not at all; a
 * change of several files is written in order, and a failure part way leaves what was written recorded.
 */
public final class ChangePipeline {
    /**
     * One value of a change: {@code expected} is the value, as text, the change was made against, or null where the user
     * chose to replace whatever the target holds; {@code value} is the new one.
     */
    public record Edit<T extends ChangeRecord.Target, V>(T target, String expected, V value) {
        public Edit {
            Objects.requireNonNull(target, "target");
        }
    }

    /** A target no longer holds the value a change was made against; its message names the target. */
    public static final class Stale extends IOException {
        public Stale(String message) {
            super(message);
        }
    }

    /** A value the change set: what it was before, and what it is now. */
    public record Applied<T extends ChangeRecord.Target>(T target, String before, String now) {
    }

    /** What a change did, and whether the connected game made it. */
    public record Outcome<T extends ChangeRecord.Target>(List<Applied<T>> applied, boolean live) {
    }

    private final GameLocation location;
    private final ChangeRecord record;
    private final Executor writes;
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<Integer, CompletableFuture<ChangeResultPayload>> waiting = new ConcurrentHashMap<>();
    private final Reloads reloads;

    /** Changes the game {@code location} tells of; {@code writes} is the project's write queue. */
    public ChangePipeline(GameLocation location, ChangeRecord record, Executor writes) {
        this.location = Objects.requireNonNull(location, "location");
        this.record = Objects.requireNonNull(record, "record");
        this.writes = Objects.requireNonNull(writes, "writes");
        this.reloads = new Reloads(location);
        location.addListener(change -> {
            if (change == GameLocation.Change.DISCONNECTED) gameDisconnected();
        });
    }

    public GameLocation location() {
        return this.location;
    }

    public ChangeRecord record() {
        return this.record;
    }

    /** The reloads that make the running game use what changes wrote. */
    public Reloads reloads() {
        return this.reloads;
    }

    /** Takes the answer of the game or the world's server to a change. */
    public void answered(ChangeResultPayload result) {
        CompletableFuture<ChangeResultPayload> request = this.waiting.remove(result.requestId());
        if (request != null) request.complete(result);
    }

    /** The game client could not carry change {@code requestId} to the world's server, for {@code reason}. */
    public void relayFailed(int requestId, String reason) {
        CompletableFuture<ChangeResultPayload> request = this.waiting.remove(requestId);
        if (request != null) request.completeExceptionally(new IOException(reason));
    }

    private void gameDisconnected() {
        for (CompletableFuture<ChangeResultPayload> request : this.waiting.values()) {
            request.completeExceptionally(new IOException("The game disconnected before it answered"));
        }
        this.waiting.clear();
    }

    /** Makes {@code edits} as one change of {@code category}. */
    public <T extends ChangeRecord.Target, V> CompletableFuture<Outcome<T>> change(ChangeCategory<T, V> category, List<Edit<T, V>> edits) {
        List<Edit<T, V>> change = List.copyOf(edits);
        if (change.isEmpty()) throw new IllegalArgumentException("A change needs an edit");
        CompletableFuture<Decided<T>> decided;
        try {
            // Where the game is and what the file holds are read after the changes queued before, never on the Swing thread.
            decided = CompletableFuture.supplyAsync(() -> {
                try {
                    GameLocation.Connection live = connection(category, this.location.read(), change);
                    return live != null ? new Decided<>(null, live) : new Decided<>(write(category, change), null);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }, this.writes);
        } catch (RejectedExecutionException closed) {
            return CompletableFuture.failedFuture(new IOException("The project is closing; nothing was changed"));
        }
        return decided.thenCompose(done -> done.live() == null ? CompletableFuture.completedFuture(done.written())
                : live(done.live(), category, change));
    }

    /** What the write queue decided: the change made in the file, or the connection that makes it. */
    private record Decided<T extends ChangeRecord.Target>(Outcome<T> written, GameLocation.Connection live) {
    }

    /** The connection of the game that makes the change, or null where the file is written; fails where it is refused. */
    private static <T extends ChangeRecord.Target, V> GameLocation.Connection connection(ChangeCategory<T, V> category, GameState game,
                                                                                        List<Edit<T, V>> change) throws IOException {
        GameLocation.Connection live = null;
        boolean files = false;
        for (Edit<T, V> edit : change) {
            switch (category.access(game, edit.target())) {
                case Access.Live access -> live = access.connection();
                case Access.Files ignored -> files = true;
                case Access.Refused refused -> throw new IOException(refused.reason());
            }
        }
        if (live != null && files) throw new IllegalArgumentException("A change goes either to the game or into files");
        return live;
    }

    /**
     * Writes a change into its files now; blocking, and only in the project's write queue, after the changes before it.
     * Every target is checked before any is written. A target that already holds its new value is only recorded, such as
     * a revert of a file someone else put back. The rest are written in order, each recorded as it lands, so a failure
     * part way leaves the written ones recorded and names them.
     */
    public <T extends ChangeRecord.Target, V> Outcome<T> write(ChangeCategory<T, V> category, List<Edit<T, V>> change) throws IOException {
        Map<T, String> held = category.readFile(change.stream().map(Edit::target).toList());
        List<ChangeCategory.Write<T, V>> writes = new ArrayList<>();
        List<Applied<T>> applied = new ArrayList<>();
        for (Edit<T, V> edit : change) {
            String now = category.text(edit.value());
            String before = held.get(edit.target());
            if (now.equals(before)) {
                applied.add(new Applied<>(edit.target(), before, now));
                continue;
            }
            if (before != null && edit.expected() != null && !before.equals(edit.expected())) {
                throw new Stale(category.changedSince(edit.target()));
            }
            writes.add(new ChangeCategory.Write<>(edit.target(), edit.value()));
            // A target the file names nothing for had the value it was shown with.
            applied.add(new Applied<>(edit.target(), before != null ? before : Objects.requireNonNullElse(edit.expected(), ""), now));
        }
        Set<T> landed = new LinkedHashSet<>();
        try {
            if (!writes.isEmpty()) category.writeFile(writes, landed::add);
        } catch (IOException failure) {
            Set<T> unwritten = new HashSet<>();
            writes.forEach(write -> unwritten.add(write.target()));
            unwritten.removeAll(landed);
            applied.stream().filter(value -> !unwritten.contains(value.target())).forEach(this::recorded);
            if (landed.isEmpty()) throw failure;
            List<String> names = landed.stream().map(category::name).toList();
            throw new IOException(failure.getMessage() + "; " + String.join(", ", names) + (names.size() == 1 ? " was" : " were")
                    + " written before that", failure);
        }
        applied.forEach(this::recorded);
        return new Outcome<>(applied, false);
    }

    /** Sends the change to the connected game, and records it whenever the game answers. */
    private <T extends ChangeRecord.Target, V> CompletableFuture<Outcome<T>> live(GameLocation.Connection connection,
                                                                                 ChangeCategory<T, V> category, List<Edit<T, V>> change) {
        int id = this.requests.incrementAndGet();
        CompletableFuture<ChangeResultPayload> answer = new CompletableFuture<>();
        this.waiting.put(id, answer);
        // The change is recorded when the game answers, however late; the caller only waits a while for it. Values the game
        // answers with an error are recorded as they are now, then the error fails the change: it was made, but did not
        // take effect.
        CompletableFuture<Outcome<T>> made = answer.thenApply(result -> {
            if (result.applied().isEmpty()) throw new CompletionException(new IOException(result.error()));
            if (result.applied().size() != change.size()) {
                throw new CompletionException(new IOException("The game answered for another number of values"));
            }
            List<Applied<T>> applied = new ArrayList<>();
            for (int index = 0; index < change.size(); index++) {
                ChangeResultPayload.Applied value = result.applied().get(index);
                applied.add(new Applied<>(change.get(index).target(), value.before(), value.now()));
            }
            applied.forEach(this::recorded);
            if (!result.error().isEmpty()) throw new CompletionException(new IOException(result.error()));
            return new Outcome<>(applied, true);
        });
        List<ChangePayload.Edit> edits = change.stream().map(edit -> new ChangePayload.Edit(category.id(),
                category.gameTarget(edit.target()), edit.expected(), category.text(edit.value()))).toList();
        AbstractMessage message = new ChangeMessage(new ChangePayload(id, edits));
        Path world = category.world(change.getFirst().target());
        try {
            // A change a world's server owns names the world, and the game client refuses it once it plays another.
            if (world != null) message = new ToServerMessage(RelayedMessages.toServer(message, id, identity(world)));
        } catch (IOException left) {
            this.waiting.remove(id);
            return CompletableFuture.failedFuture(left);
        }
        if (!connection.send(message)) {
            this.waiting.remove(id);
            return CompletableFuture.failedFuture(new IOException("The game is not connected"));
        }
        CompletableFuture<Outcome<T>> waited = made.copy();
        CompletableFuture.delayedExecutor(category.answerWait().toMillis(), TimeUnit.MILLISECONDS).execute(() -> waited.completeExceptionally(
                new IOException("The game has not answered yet; the change is recorded if the game makes it")));
        return waited;
    }

    /** {@code world} as {@code PLAYING} names it; fails when the game plays another world. */
    private String identity(Path world) throws IOException {
        PlayingPayload playing = this.location.playing();
        if (playing instanceof PlayingPayload.Singleplayer singleplayer
                && Path.of(singleplayer.world()).toAbsolutePath().normalize().equals(world.toAbsolutePath().normalize())) {
            return playing.identity();
        }
        throw new IOException("The game went to another world before the change reached it");
    }

    private void recorded(Applied<?> applied) {
        this.record.changed(applied.target(), applied.before(), applied.now());
    }
}
