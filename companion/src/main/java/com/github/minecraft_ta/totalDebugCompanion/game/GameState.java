package com.github.minecraft_ta.totalDebugCompanion.game;

import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One reading of where the game is, and how a change of what the game or a world owns can be made then; see
 * {@code docs/GAME_LOCATION.md}. The worlds' locks are read only by the queries that need them, so those are blocking.
 */
public final class GameState {
    /** The game of the instance. */
    public sealed interface Game {
        /** No game runs in the instance. */
        record Closed() implements Game {
        }

        /** A game runs but is not connected. */
        record Unconnected() implements Game {
        }

        /** The connected game: its process, or 0 while unknown, and what it plays, or null until it has said. */
        record Connected(GameLocation.Connection connection, long process, PlayingPayload playing) implements Game {
            public Connected {
                Objects.requireNonNull(connection, "connection");
            }
        }
    }

    /** The folder of the instance under which the change record keeps the worlds of servers. */
    private static final String SERVER_WORLDS = "total-debug/servers";

    private final Path workspace;
    private final GameLocation.Files files;
    private final Game game;
    private boolean heldRead;
    private Path held;

    GameState(Path workspace, GameLocation.Files files, Game game) {
        this.workspace = workspace;
        this.files = files;
        this.game = game;
    }

    public Game game() {
        return this.game;
    }

    /** Whether a game runs in the instance, connected or not. */
    public boolean running() {
        return !(this.game instanceof Game.Closed);
    }

    public boolean connected() {
        return this.game instanceof Game.Connected;
    }

    /**
     * How a change of what the game owns, such as {@code options.txt}, can be made: live in the connected game, in the
     * files while no game runs, and not at all while a game runs without a connection, since it writes its own copy over
     * Companion's. {@code purpose} ends the refusal, such as "change its resource packs".
     */
    public Access client(String purpose) {
        return switch (this.game) {
            case Game.Connected connected -> new Access.Live(connected.connection());
            case Game.Unconnected ignored -> new Access.Refused(
                    "The game is running but not connected to Companion; connect it, or close it, to " + purpose);
            case Game.Closed ignored -> new Access.Files();
        };
    }

    /**
     * How a change of what {@code world}'s server owns, such as its {@code level.dat} or its datapacks, can be made: live
     * while the connected game plays it, in its files while nothing holds it, and not at all while another game or
     * program has it open. Blocking: it reads the world's lock.
     */
    public Access world(Path world, String purpose) {
        Path normalized = normalize(world);
        if (isServerWorld(normalized)) return serverWorld(normalized, purpose);
        if (this.game instanceof Game.Connected connected && plays(normalized)) return new Access.Live(connected.connection());
        if (!this.files.held(normalized)) return new Access.Files();
        String name = normalized.getFileName().toString();
        return new Access.Refused(switch (this.game) {
            case Game.Connected connected when connected.playing() == null ->
                    "The world " + name + " is open, and the game has not said yet whether it plays it; try again in a moment to " + purpose;
            case Game.Unconnected ignored ->
                    "The world " + name + " is open in a game that is not connected to Companion; connect it, or close the world, to " + purpose;
            default -> "The world " + name + " is open in another program; close it to " + purpose;
        });
    }

    /**
     * How a change of the world of a server, which is on the server rather than in the instance, can be made: live through
     * the relay while the connected game plays on it and the server has TotalDebug, and never in files.
     */
    private Access serverWorld(Path world, String purpose) {
        Optional<PlayingPayload.Multiplayer> server = server().filter(playing -> serverWorld(playing).equals(world));
        if (server.isEmpty()) {
            return new Access.Refused("The world of " + world.getFileName() + " is on its server; join it in the game to " + purpose);
        }
        if (!server.get().totalDebug()) {
            return new Access.Refused("The server " + server.get().address() + " does not have TotalDebug, which Companion needs to " + purpose);
        }
        return new Access.Live(((Game.Connected) this.game).connection());
    }

    /**
     * Where the change record keeps the world of {@code server}: a place under the instance's {@code total-debug} folder
     * named after the address the game joined, since the world itself is on the server.
     */
    public Path serverWorld(PlayingPayload.Multiplayer server) {
        return this.workspace.resolve(SERVER_WORLDS).resolve(encode(server.address())).toAbsolutePath().normalize();
    }

    /**
     * How the user names a world: a folder's name, or for the place the change record keeps a server's world, the address
     * of the server.
     */
    public static String worldName(Path world) {
        Path parent = world.getParent();
        boolean server = parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("servers")
                && parent.getParent() != null && parent.getParent().getFileName() != null
                && parent.getParent().getFileName().toString().equals("total-debug");
        String name = world.getFileName().toString();
        return server ? URLDecoder.decode(name, StandardCharsets.UTF_8) : name;
    }

    /**
     * {@code address} as a file name that names only it: every character but letters, digits, {@code .} and {@code -} as
     * {@code %} and the hexadecimal of its UTF-8 bytes, so no two addresses share the change record's place.
     */
    private static String encode(String address) {
        StringBuilder name = new StringBuilder();
        for (byte value : address.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (value & 0xFF);
            if (c < 0x80 && (Character.isLetterOrDigit(c) || c == '.' || c == '-')) name.append(c);
            else name.append('%').append(String.format("%02X", value & 0xFF));
        }
        return name.isEmpty() ? "%" : name.toString();
    }

    /** Whether {@code world} is where the change record keeps the world of a server. */
    public boolean isServerWorld(Path world) {
        return normalize(world).startsWith(this.workspace.resolve(SERVER_WORLDS).toAbsolutePath().normalize());
    }

    /** Whether the connected game plays {@code world}: in singleplayer, or as the world of the server it plays on. */
    public boolean plays(Path world) {
        Path normalized = normalize(world);
        if (!(this.game instanceof Game.Connected connected)) return false;
        return switch (connected.playing()) {
            case PlayingPayload.Singleplayer singleplayer -> normalize(Path.of(singleplayer.world())).equals(normalized);
            case PlayingPayload.Multiplayer server -> serverWorld(server).equals(normalized);
            case null, default -> false;
        };
    }

    /** {@code world} as {@code PLAYING} names it, while the connected game plays it; the relay checks a request by it. */
    public Optional<String> identity(Path world) {
        return plays(world) ? Optional.of(((Game.Connected) this.game).playing().identity()) : Optional.empty();
    }

    /**
     * The world the game has open: the one the connected game plays in singleplayer; the held one while the game runs
     * without a connection, or while the connected game has not said yet what it plays; null otherwise. Blocking.
     */
    public Path openWorld() {
        return switch (this.game) {
            case Game.Connected connected when connected.playing() == null -> held();
            case Game.Connected connected -> connected.playing() instanceof PlayingPayload.Singleplayer singleplayer
                    ? normalize(Path.of(singleplayer.world())) : null;
            case Game.Unconnected ignored -> held();
            case Game.Closed ignored -> null;
        };
    }

    /** Whether the game has {@code world} open, as {@link #openWorld()} says. Blocking. */
    public boolean isOpen(Path world) {
        Path open = openWorld();
        return open != null && open.equals(normalize(world));
    }

    /** The current world: the one the game has open, otherwise the one played last; null without worlds. Blocking. */
    public Path currentWorld() {
        Path open = openWorld();
        if (open != null) return open;
        Path last = this.files.lastPlayed();
        return last == null ? null : normalize(last);
    }

    /** The server the connected game plays on, when it plays on one. */
    public Optional<PlayingPayload.Multiplayer> server() {
        return this.game instanceof Game.Connected connected && connected.playing() instanceof PlayingPayload.Multiplayer server
                ? Optional.of(server) : Optional.empty();
    }

    /** The game directory. */
    public Path workspace() {
        return this.workspace;
    }

    /** A held world of the instance, or null, read once for this state. */
    private synchronized Path held() {
        if (!this.heldRead) {
            Path found = this.files.heldWorld();
            this.held = found == null ? null : normalize(found);
            this.heldRead = true;
        }
        return this.held;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
