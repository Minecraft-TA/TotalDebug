package com.github.minecraft_ta.totalDebugCompanion.game;

import com.github.minecraft_ta.totalDebugCompanion.catalog.Worlds;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.storage.GameLock;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.tth05.scnet.message.AbstractMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Where a project's game is, as {@code docs/GAME_LOCATION.md} describes: closed, running without a connection, or
 * connected, and what the connected game plays. The rest of Companion asks it instead of checking the game's locks or
 * the connection itself. The connection and what the game says arrive here and change the state at once; the locks are
 * read by {@link #read()}.
 */
public final class GameLocation {
    /** How the connected game is reached; false when the message could not be sent. */
    @FunctionalInterface
    public interface Connection {
        boolean send(AbstractMessage message);
    }

    /** What changed, told to listeners on the thread that changed it. */
    public enum Change { CONNECTED, DISCONNECTED, PROCESS, PLAYING }

    /** The instance's files that tell whether a game runs and whether a world is held. Blocking. */
    public interface Files {
        /** Whether a game holds the instance's game lock; a lock that cannot be checked counts as held. */
        boolean gameRunning();

        /** Whether some program holds the world's {@code session.lock}. */
        boolean held(Path world);

        /** A held world of the instance's {@code saves}, or null. */
        Path heldWorld();

        /** The world played last, or null without worlds. */
        Path lastPlayed();

        /** The files of the game directory {@code workspace}. */
        static Files of(Path workspace) {
            Path gameLock = InstancePaths.forGame(workspace).gameLock();
            return new Files() {
                @Override public boolean gameRunning() { return GameLock.held(gameLock); }
                @Override public boolean held(Path world) { return Worlds.isOpen(world); }
                @Override public Path heldWorld() { return Worlds.open(workspace); }
                @Override public Path lastPlayed() { return Worlds.lastPlayed(workspace); }
            };
        }
    }

    /** The connected game: how it is reached, its process or 0 while unknown, and what it plays, or null until told. */
    record Link(Connection connection, long process, PlayingPayload playing) {
    }

    private final Path workspace;
    private final Files files;
    private final List<Consumer<Change>> listeners = new CopyOnWriteArrayList<>();
    private volatile Link link;
    /**
     * What the game told on the current connection, kept from its first message: Companion may take the connection as
     * established only after the game has spoken, such as after a reconnect's teardown.
     */
    private long toldProcess;
    private PlayingPayload toldPlaying;

    /** The location of the game of the game directory {@code workspace}. */
    public GameLocation(Path workspace) {
        this(workspace, Files.of(workspace));
    }

    public GameLocation(Path workspace, Files files) {
        this.workspace = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
        this.files = Objects.requireNonNull(files, "files");
    }

    /** The game directory. */
    public Path workspace() {
        return this.workspace;
    }

    /**
     * The game's connection is established, with what the game has told on it so far; listeners hear of that as well, as
     * if it was told now.
     */
    public void connected(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        Link established;
        synchronized (this) {
            established = new Link(connection, this.toldProcess, this.toldPlaying);
            this.link = established;
        }
        tell(Change.CONNECTED);
        if (established.process() != 0) tell(Change.PROCESS);
        if (established.playing() != null) tell(Change.PLAYING);
    }

    /** The game's process, as it announced it on the current connection. */
    public void process(long process) {
        synchronized (this) {
            this.toldProcess = process;
            Link current = this.link;
            if (current == null) return;
            this.link = new Link(current.connection(), process, current.playing());
        }
        tell(Change.PROCESS);
    }

    /** What the game plays, as it told on the current connection. */
    public void playing(PlayingPayload playing) {
        Objects.requireNonNull(playing, "playing");
        synchronized (this) {
            this.toldPlaying = playing;
            Link current = this.link;
            if (current == null) return;
            this.link = new Link(current.connection(), current.process(), playing);
        }
        tell(Change.PLAYING);
    }

    /** The game's connection ended; what it told on it no longer holds. */
    public void disconnected() {
        synchronized (this) {
            this.toldProcess = 0;
            this.toldPlaying = null;
            if (this.link == null) return;
            this.link = null;
        }
        tell(Change.DISCONNECTED);
    }

    /** The connection to the game, or null without one. Not blocking. */
    public Connection connection() {
        Link current = this.link;
        return current == null ? null : current.connection();
    }

    /** What the game told it plays on the current connection, or null. Not blocking. */
    public synchronized PlayingPayload playing() {
        return this.toldPlaying;
    }

    /** The connected game's process, or 0 while unknown or without a connection. Not blocking. */
    public long process() {
        Link current = this.link;
        return current == null ? 0 : current.process();
    }

    /** The state as it is now. Blocking: without a connection it reads the game's lock; the worlds' locks are read by the queries. */
    public GameState read() {
        Link current = this.link;
        if (current != null) {
            return new GameState(this.workspace, this.files,
                    new GameState.Game.Connected(current.connection(), current.process(), current.playing()));
        }
        if (!this.files.gameRunning()) return new GameState(this.workspace, this.files, new GameState.Game.Closed());
        return new GameState(this.workspace, this.files, new GameState.Game.Unconnected());
    }

    /** Tells {@code listener} of each change, on the thread that made it; returns what removes it. */
    public Runnable addListener(Consumer<Change> listener) {
        this.listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> this.listeners.remove(listener);
    }

    private void tell(Change change) {
        this.listeners.forEach(listener -> listener.accept(change));
    }
}
