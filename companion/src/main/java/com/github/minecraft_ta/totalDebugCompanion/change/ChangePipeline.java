package com.github.minecraft_ta.totalDebugCompanion.change;

import com.github.minecraft_ta.totalDebugCompanion.game.Access;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.ChangePayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ChangeResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ChangeMessage;

import java.io.IOException;
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
 * The one path of a change Companion makes to values the game keeps (see {@code docs/CHANGE_PIPELINE.md}). A change
 * waits in the project's write queue behind the changes before it; its category decides whether it goes to the connected
 * game or into the file, or is refused; every target must still hold the value the change was made against before any
 * is set; and each value set is entered in the change record. A change is made whole or not at all.
 */
public final class ChangePipeline {
    private static final long ANSWER_SECONDS = 5;

    /** One value of a change: {@code expected} is the value it was made against, {@code value} the new one. */
    public record Edit<T extends ChangeRecord.Target>(T target, String expected, String value) {
        public Edit {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(value, "value");
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

    /** Changes the game {@code location} tells of; {@code writes} is the project's write queue. */
    public ChangePipeline(GameLocation location, ChangeRecord record, Executor writes) {
        this.location = Objects.requireNonNull(location, "location");
        this.record = Objects.requireNonNull(record, "record");
        this.writes = Objects.requireNonNull(writes, "writes");
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

    /** Takes the game's answer to a change. */
    public void answered(ChangeResultPayload result) {
        CompletableFuture<ChangeResultPayload> request = this.waiting.remove(result.requestId());
        if (request != null) request.complete(result);
    }

    private void gameDisconnected() {
        for (CompletableFuture<ChangeResultPayload> request : this.waiting.values()) {
            request.completeExceptionally(new IOException("The game disconnected before it answered"));
        }
        this.waiting.clear();
    }

    /** Makes {@code edits} as one change of {@code category}. */
    public <T extends ChangeRecord.Target> CompletableFuture<Outcome<T>> change(ChangeCategory<T> category, List<Edit<T>> edits) {
        List<Edit<T>> change = List.copyOf(edits);
        if (change.isEmpty()) throw new IllegalArgumentException("A change needs an edit");
        CompletableFuture<Decided<T>> decided;
        try {
            // Where the game is and what the file holds are read after the changes queued before, never on the Swing thread.
            decided = CompletableFuture.supplyAsync(() -> {
                try {
                    GameLocation.Connection live = connection(category, this.location.read(), change);
                    return live != null ? new Decided<>(null, live) : new Decided<>(writeFile(category, change), null);
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
    private static <T extends ChangeRecord.Target> GameLocation.Connection connection(ChangeCategory<T> category, GameState game,
                                                                                     List<Edit<T>> change) throws IOException {
        GameLocation.Connection live = null;
        boolean files = false;
        for (Edit<T> edit : change) {
            switch (category.access(game, edit.target())) {
                case Access.Live access -> live = access.connection();
                case Access.Files ignored -> files = true;
                case Access.Refused refused -> throw new IOException(refused.reason());
            }
        }
        if (live != null && files) throw new IllegalArgumentException("A change goes either to the game or into files");
        return live;
    }

    /** Writes the change into the file, after checking every target still holds the value the change expects. */
    private <T extends ChangeRecord.Target> Outcome<T> writeFile(ChangeCategory<T> category, List<Edit<T>> change) throws IOException {
        Map<T, String> held = category.readFile(change.stream().map(Edit::target).toList());
        Map<T, String> values = new LinkedHashMap<>();
        List<Applied<T>> applied = new ArrayList<>();
        for (Edit<T> edit : change) {
            String before = held.get(edit.target());
            if (before != null && !before.equals(edit.expected())) {
                throw new IOException(category.name(edit.target()) + " changed in its file since Companion read it");
            }
            values.put(edit.target(), edit.value());
            // A target the file names nothing for had the value it was shown with.
            applied.add(new Applied<>(edit.target(), before != null ? before : edit.expected(), edit.value()));
        }
        category.writeFile(values);
        applied.forEach(this::recorded);
        return new Outcome<>(applied, false);
    }

    /** Sends the change to the connected game, and records it whenever the game answers. */
    private <T extends ChangeRecord.Target> CompletableFuture<Outcome<T>> live(GameLocation.Connection connection,
                                                                              ChangeCategory<T> category, List<Edit<T>> change) {
        int id = this.requests.incrementAndGet();
        CompletableFuture<ChangeResultPayload> answer = new CompletableFuture<>();
        this.waiting.put(id, answer);
        // The change is recorded when the game answers, however late; the caller only waits a while for it.
        CompletableFuture<Outcome<T>> made = answer.thenApply(result -> {
            if (!result.error().isEmpty()) throw new CompletionException(new IOException(result.error()));
            if (result.applied().size() != change.size()) {
                throw new CompletionException(new IOException("The game answered for another number of values"));
            }
            List<Applied<T>> applied = new ArrayList<>();
            for (int index = 0; index < change.size(); index++) {
                ChangeResultPayload.Applied value = result.applied().get(index);
                applied.add(new Applied<>(change.get(index).target(), value.before(), value.now()));
            }
            applied.forEach(this::recorded);
            return new Outcome<>(applied, true);
        });
        List<ChangePayload.Edit> edits = change.stream().map(edit -> new ChangePayload.Edit(category.id(),
                category.name(edit.target()), edit.expected(), edit.value())).toList();
        if (!connection.send(new ChangeMessage(new ChangePayload(id, edits)))) {
            this.waiting.remove(id);
            return CompletableFuture.failedFuture(new IOException("The game is not connected"));
        }
        CompletableFuture<Outcome<T>> waited = made.copy();
        CompletableFuture.delayedExecutor(ANSWER_SECONDS, TimeUnit.SECONDS).execute(() -> waited.completeExceptionally(
                new IOException("The game has not answered yet; the change is recorded if the game makes it")));
        return waited;
    }

    private void recorded(Applied<?> applied) {
        this.record.changed(applied.target(), applied.before(), applied.now());
    }
}
