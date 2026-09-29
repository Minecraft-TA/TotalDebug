package com.github.minecraft_ta.totalDebugCompanion.change;

import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessages;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReloadMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ToServerMessage;
import com.github.tth05.scnet.message.AbstractMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * The reloads that make the running game use what changes wrote (see {@code docs/CHANGE_PIPELINE.md}), in one queue for
 * each owner: the client's resources, reloaded by the game client on the connection they were asked on, and a world's
 * data, reloaded by its server through the relay, for that world. Reloads asked for while one of the same queue runs, or
 * while a write is still queued, run together as one more reload after it. Each is answered with {@code RELOAD_RESULT},
 * which also answers the requests sent with {@link #askServer}, such as a datapack selection.
 */
public final class Reloads {
    private static final long RELOAD_MINUTES = 10;

    /**
     * Reloads to send together, to the game on one connection; {@code world} is the world whose data they reload, and
     * {@code identity} that world as {@code PLAYING} names it, or both null.
     */
    private static final class Batch {
        final GameLocation.Connection connection;
        final Path world;
        final String identity;
        final Set<ReloadPayload.Kind> kinds = EnumSet.noneOf(ReloadPayload.Kind.class);
        final Set<String> watched = new LinkedHashSet<>();
        final CompletableFuture<ReloadResultPayload> result = new CompletableFuture<>();
        /** The pack Companion manages that the game enables on top of this batch's stack first, or empty. */
        String managedPack = "";

        Batch(GameLocation.Connection connection, Path world, String identity) {
            this.connection = connection;
            this.world = world;
            this.identity = identity;
        }
    }

    /** One owner's reloads: the one the game runs, and the next, which takes what is asked for meanwhile. Under the lock of Reloads. */
    private final class Queue {
        private Batch running;
        private Batch next;

        CompletableFuture<ReloadResultPayload> add(GameLocation.Connection connection, Path world, String identity,
                                                   ReloadPayload.Kind kind, String path, String managedPack) {
            // Asked for on an earlier connection: that game is gone, and the one connected now never saw these writes.
            if (this.next != null && this.next.connection != connection) drop("The game disconnected before it reloaded");
            if (this.next != null && !Objects.equals(this.next.world, world)) drop("The game went to another world before it reloaded");
            if (this.next == null) this.next = new Batch(connection, world, identity);
            Batch batch = this.next;
            batch.kinds.add(kind);
            batch.watched.add(path);
            if (!managedPack.isEmpty()) batch.managedPack = managedPack;
            sendIfIdle();
            return batch.result;
        }

        /** Fails the waiting reload's changes with {@code reason}; the game never saw what it would reload. */
        void drop(String reason) {
            if (this.next == null) return;
            this.next.result.completeExceptionally(new IOException(reason));
            this.next = null;
        }

        /** Sends the waiting reload, unless one runs or a write that would join it is still queued. */
        void sendIfIdle() {
            if (this.running != null || Reloads.this.writing > 0 || this.next == null) return;
            Batch batch = this.next;
            this.next = null;
            this.running = batch;
            // A full reload covers the language and textures.
            if (batch.kinds.contains(ReloadPayload.Kind.RESOURCES)) {
                batch.kinds.remove(ReloadPayload.Kind.LANGUAGE);
                batch.kinds.remove(ReloadPayload.Kind.TEXTURES);
            }
            List<String> watched = new ArrayList<>(batch.watched);
            if (watched.size() > ReloadPayload.MAX_WATCHED) watched = watched.subList(0, ReloadPayload.MAX_WATCHED);
            boolean data = batch.world != null;
            List<String> sent = watched;
            IntFunction<AbstractMessage> reload = id -> new ReloadMessage(new ReloadPayload(id, batch.kinds,
                    data ? "" : batch.managedPack, data ? batch.managedPack : "", sent));
            (data ? askServer(batch.connection, batch.identity, reload) : ask(batch.connection, reload)).whenComplete((answer, failure) -> {
                if (failure != null) batch.result.completeExceptionally(failure);
                else batch.result.complete(answer);
                synchronized (Reloads.this) {
                    if (this.running != batch) return;
                    this.running = null;
                    sendIfIdle();
                }
            });
        }
    }

    private final GameLocation location;
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<Integer, CompletableFuture<ReloadResultPayload>> waiting = new ConcurrentHashMap<>();
    private final Queue resources = new Queue();
    private final Queue data = new Queue();
    /** Writes queued but not yet past asking for their reload; the next reloads wait for them. */
    private int writing;

    public Reloads(GameLocation location) {
        this.location = Objects.requireNonNull(location, "location");
        location.addListener(change -> {
            if (change == GameLocation.Change.DISCONNECTED) gameDisconnected();
            else if (change == GameLocation.Change.PLAYING) leftWorld();
        });
    }

    /** A write is queued whose reload the next reloads wait for, so writes in quick succession take one reload. */
    public synchronized void writing() {
        this.writing++;
    }

    /** A write {@link #writing} announced has asked for its reload, or failed. */
    public synchronized void written() {
        this.writing--;
        this.resources.sendIfIdle();
        this.data.sendIfIdle();
    }

    /** Reloads the client's resources of {@code kind} for {@code path}, enabling {@code managedPack} on top first, or none. */
    public synchronized CompletableFuture<ReloadResultPayload> resources(GameLocation.Connection connection, ReloadPayload.Kind kind,
                                                                         String path, String managedPack) {
        return this.resources.add(connection, null, null, kind, path, managedPack);
    }

    /**
     * Reloads the data of {@code world} on its server for {@code path}, enabling {@code managedPack} first. The game must
     * still play the world; its server is reached through the game client on {@code connection}.
     */
    public synchronized CompletableFuture<ReloadResultPayload> data(GameLocation.Connection connection, Path world, String path,
                                                                    String managedPack) {
        String identity;
        try {
            identity = identity(world);
        } catch (IOException left) {
            return CompletableFuture.failedFuture(left);
        }
        return this.data.add(connection, world.toAbsolutePath().normalize(), identity, ReloadPayload.Kind.DATA, path, managedPack);
    }

    /**
     * {@code world} as {@code PLAYING} names it, the identity the relay checks a message for the world's server by; fails
     * when the game plays another world.
     */
    public String identity(Path world) throws IOException {
        return this.location.read().identity(world).orElseThrow(() -> new IOException("The game went to another world before it reloaded"));
    }

    /**
     * Sends the request {@code request} makes of an id to the game on {@code connection}, which answers it with a
     * {@code RELOAD_RESULT} of that id; completes with the answer, or fails once that connection ended.
     */
    private CompletableFuture<ReloadResultPayload> ask(GameLocation.Connection connection, IntFunction<AbstractMessage> request) {
        return send(connection, request);
    }

    /**
     * Sends the request {@code request} makes of an id to the server of the world {@code world} names, as {@code PLAYING}
     * names it, through the game client on {@code connection}; the server answers it with a {@code RELOAD_RESULT} of that
     * id, and a failure to carry it, {@link #relayFailed}, fails it.
     */
    public CompletableFuture<ReloadResultPayload> askServer(GameLocation.Connection connection, String world,
                                                            IntFunction<AbstractMessage> request) {
        return send(connection, id -> new ToServerMessage(RelayedMessages.toServer(request.apply(id), id, world)));
    }

    private CompletableFuture<ReloadResultPayload> send(GameLocation.Connection connection, IntFunction<AbstractMessage> request) {
        int id = this.requests.incrementAndGet();
        CompletableFuture<ReloadResultPayload> result = new CompletableFuture<>();
        this.waiting.put(id, result);
        try {
            if (connection == null || !connection.send(request.apply(id))) {
                result.completeExceptionally(new IOException("The game is not connected"));
            }
        } catch (RuntimeException unsendable) {
            // A request that cannot be sent fails; the next reload is not held up behind it.
            result.completeExceptionally(unsendable);
        }
        return result.orTimeout(RELOAD_MINUTES, TimeUnit.MINUTES).whenComplete((ignored, failure) -> this.waiting.remove(id));
    }

    /** The game client could not carry request {@code correlation} to the server, for {@code reason}. */
    public void relayFailed(int correlation, String reason) {
        CompletableFuture<ReloadResultPayload> request = this.waiting.remove(correlation);
        if (request != null) request.completeExceptionally(new IOException(reason));
    }

    /** Takes the answer to a reload or another request sent with {@link #askServer}. */
    public void answered(ReloadResultPayload result) {
        CompletableFuture<ReloadResultPayload> request = this.waiting.remove(result.requestId());
        if (request != null) request.complete(result);
    }

    private void gameDisconnected() {
        for (CompletableFuture<ReloadResultPayload> request : this.waiting.values()) {
            request.completeExceptionally(new IOException("The game disconnected before it finished reloading"));
        }
        this.waiting.clear();
    }

    /** Drops the waiting data reload of a world the game no longer plays, which never saw these writes. */
    private synchronized void leftWorld() {
        if (this.data.next != null && !this.location.read().plays(this.data.next.world)) {
            this.data.drop("The game went to another world before it reloaded");
        }
    }
}
