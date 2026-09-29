package com.github.minecraft_ta.totalDebugCompanion.game;

import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The states of docs/GAME_LOCATION.md and the answer each gives. */
class GameStateTest {
    private static final Path GAME = Path.of("game").toAbsolutePath();
    private static final Path WORLD = GAME.resolve("saves/World");
    private static final Path OTHER = GAME.resolve("saves/Other");
    private static final GameLocation.Connection SEND = message -> true;

    /** How the test's game is: whether it runs, which worlds are held and which world was played last. */
    private record Files(boolean running, Set<Path> held, Path lastPlayed) implements GameLocation.Files {
        @Override public boolean gameRunning() { return this.running; }
        @Override public boolean held(Path world) { return this.held.contains(world); }
        @Override public Path heldWorld() { return this.held.stream().findFirst().orElse(null); }
        @Override public Path lastPlayed() { return this.lastPlayed; }
    }

    /** A setup: the game, what it plays, the held worlds. */
    private record Setup(String name, boolean running, boolean connected, PlayingPayload playing, Set<Path> held) {
        GameState read() {
            GameLocation location = new GameLocation(GAME, new Files(this.running, this.held, OTHER));
            if (this.connected) {
                location.connected(SEND);
                if (this.playing != null) location.playing(this.playing);
            }
            return location.read();
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

    private static final Setup CLOSED = new Setup("closed", false, false, null, Set.of());
    private static final Setup CLOSED_WORLD_HELD = new Setup("closed, world held by another program", false, false, null, Set.of(WORLD));
    private static final Setup UNCONNECTED = new Setup("running, not connected", true, false, null, Set.of());
    private static final Setup UNCONNECTED_IN_WORLD = new Setup("running, not connected, in the world", true, false, null, Set.of(WORLD));
    private static final Setup CONNECTED_UNTOLD = new Setup("connected, not told yet, world held", true, true, null, Set.of(WORLD));
    private static final Setup MENU = new Setup("connected, at the menu", true, true, new PlayingPayload.Menu(), Set.of());
    private static final Setup MENU_WORLD_HELD = new Setup("connected, at the menu, world held elsewhere", true, true,
            new PlayingPayload.Menu(), Set.of(WORLD));
    private static final Setup SINGLEPLAYER = new Setup("connected, playing the world", true, true,
            new PlayingPayload.Singleplayer(WORLD.toString()), Set.of(WORLD));
    private static final Setup SINGLEPLAYER_OTHER = new Setup("connected, playing another world, world held elsewhere", true, true,
            new PlayingPayload.Singleplayer(OTHER.toString()), Set.of(OTHER, WORLD));
    private static final Setup MULTIPLAYER = new Setup("connected, on a server", true, true,
            new PlayingPayload.Multiplayer("play.example.com", false, true), Set.of());

    static Stream<Arguments> clientAccess() {
        return Stream.of(
                Arguments.of(CLOSED, Access.Files.class),
                Arguments.of(UNCONNECTED, Access.Refused.class),
                Arguments.of(CONNECTED_UNTOLD, Access.Live.class),
                Arguments.of(MENU, Access.Live.class),
                Arguments.of(SINGLEPLAYER, Access.Live.class),
                Arguments.of(MULTIPLAYER, Access.Live.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void clientAccess(Setup setup, Class<? extends Access> expected) {
        assertInstanceOf(expected, setup.read().client("change keys"));
    }

    static Stream<Arguments> worldAccess() {
        return Stream.of(
                Arguments.of(CLOSED, Access.Files.class, ""),
                Arguments.of(CLOSED_WORLD_HELD, Access.Refused.class, "The world World is open in another program; close it to change its rules"),
                Arguments.of(UNCONNECTED, Access.Files.class, ""),
                Arguments.of(UNCONNECTED_IN_WORLD, Access.Refused.class,
                        "The world World is open in a game that is not connected to Companion; connect it, or close the world, to change its rules"),
                Arguments.of(CONNECTED_UNTOLD, Access.Refused.class,
                        "The world World is open, and the game has not said yet whether it plays it; try again in a moment to change its rules"),
                Arguments.of(MENU, Access.Files.class, ""),
                Arguments.of(MENU_WORLD_HELD, Access.Refused.class, "The world World is open in another program; close it to change its rules"),
                Arguments.of(SINGLEPLAYER, Access.Live.class, ""),
                Arguments.of(SINGLEPLAYER_OTHER, Access.Refused.class, "The world World is open in another program; close it to change its rules"),
                Arguments.of(MULTIPLAYER, Access.Files.class, ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void worldAccess(Setup setup, Class<? extends Access> expected, String reason) {
        Access access = setup.read().world(WORLD, "change its rules");
        assertInstanceOf(expected, access);
        if (access instanceof Access.Refused refused) assertEquals(reason, refused.reason());
    }

    @Test
    void theClientRefusalNamesWhatToDo() {
        Access.Refused refused = assertInstanceOf(Access.Refused.class, UNCONNECTED.read().client("change keys"));
        assertEquals("The game is running but not connected to Companion; connect it, or close it, to change keys", refused.reason());
    }

    @Test
    void theOpenWorldIsTheOneTheGameHasOpen() {
        assertNull(CLOSED.read().openWorld());
        assertNull(CLOSED_WORLD_HELD.read().openWorld(), "a world held while no game runs is open in another program");
        assertEquals(WORLD, UNCONNECTED_IN_WORLD.read().openWorld(), "a game without a connection has the held world open");
        assertEquals(WORLD, CONNECTED_UNTOLD.read().openWorld(), "until the game says what it plays, the held world is taken as its own");
        assertNull(MENU_WORLD_HELD.read().openWorld());
        assertEquals(WORLD, SINGLEPLAYER.read().openWorld());
        assertEquals(OTHER, SINGLEPLAYER_OTHER.read().openWorld());
        assertNull(MULTIPLAYER.read().openWorld(), "a server's world is on its own machine");

        assertTrue(SINGLEPLAYER.read().plays(WORLD));
        assertFalse(UNCONNECTED_IN_WORLD.read().plays(WORLD), "only the connected game says what it plays");
        assertTrue(UNCONNECTED_IN_WORLD.read().isOpen(WORLD));
    }

    @Test
    void theCurrentWorldIsTheOpenOneOtherwiseTheOnePlayedLast() {
        assertEquals(WORLD, SINGLEPLAYER.read().currentWorld());
        assertEquals(OTHER, MULTIPLAYER.read().currentWorld());
        assertEquals(OTHER, CLOSED.read().currentWorld());
    }

    @Test
    void theServerTheGamePlaysOnIsKnown() {
        assertEquals("play.example.com", MULTIPLAYER.read().server().orElseThrow().address());
        assertTrue(SINGLEPLAYER.read().server().isEmpty());
        assertTrue(CLOSED.read().server().isEmpty());
    }

    @Test
    void whatTheGameToldBeforeItsConnectionWasEstablishedCounts() {
        GameLocation location = new GameLocation(GAME, new Files(true, Set.of(WORLD), null));
        location.process(7);
        location.playing(new PlayingPayload.Singleplayer(WORLD.toString()));
        List<GameLocation.Change> heard = new ArrayList<>();
        location.addListener(heard::add);

        location.connected(SEND);

        assertEquals(List.of(GameLocation.Change.CONNECTED, GameLocation.Change.PROCESS, GameLocation.Change.PLAYING), heard,
                "what the game told before is heard as told now");
        location.process(7);
        location.playing(new PlayingPayload.Singleplayer(WORLD.toString()));
        assertEquals(3, heard.size(), "the game telling the same process and world again after the handshake changes nothing");
        assertEquals(7, location.process());
        assertInstanceOf(Access.Live.class, location.read().world(WORLD, "change its rules"));

        location.disconnected();
        location.connected(SEND);
        assertEquals(0, location.process(), "what the game told on an ended connection no longer holds");
        assertFalse(location.read().plays(WORLD));
        assertInstanceOf(Access.Refused.class, location.read().world(WORLD, "change its rules"));
    }

    @Test
    void listenersHearEachChangeOnce() {
        GameLocation location = new GameLocation(GAME, new Files(false, Set.of(), null));
        List<GameLocation.Change> heard = new ArrayList<>();
        location.addListener(heard::add);

        location.process(7);
        location.playing(new PlayingPayload.Menu());
        location.disconnected();
        assertEquals(List.of(), heard, "without a connection, nothing changes");

        location.connected(SEND);
        location.process(7);
        location.playing(new PlayingPayload.Menu());
        location.disconnected();
        location.disconnected();
        assertEquals(List.of(GameLocation.Change.CONNECTED, GameLocation.Change.PROCESS, GameLocation.Change.PLAYING,
                GameLocation.Change.DISCONNECTED), heard);
        assertEquals(0, location.process());
        assertNull(location.connection());
        assertFalse(location.read().running());
    }
}
