package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.PlayingMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RelayFailedMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerScriptsMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerScriptsRequestMessage;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.message.IMessageBus;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Whether the server the game plays runs this player's scripts (docs/GAME_MESSAGES.md), with the session's own routes and
 * the messages posted as the game sends them.
 */
class ServerScriptAccessTest {
    /** A request sent to a world's server. */
    private record Sent(int correlation, String world) {
    }

    private static final PlayingPayload FIRST = new PlayingPayload.Singleplayer("C:/game/saves/First");
    private static final PlayingPayload SECOND = new PlayingPayload.Singleplayer("C:/game/saves/Second");

    private final CompanionSession session = new CompanionSession("server-script-access-test-token");
    private final List<Sent> sent = new ArrayList<>();
    private final List<String> told = new ArrayList<>();
    private final AtomicInteger runsEnded = new AtomicInteger();
    private final AtomicBoolean sends = new AtomicBoolean(true);
    private final AtomicBoolean admitted = new AtomicBoolean(true);
    private IMessageBus game;
    private ServerScriptAccess access;

    @BeforeEach
    void open() {
        // The game's messages, posted on the session's bus as if the game had sent them on an authenticated connection.
        this.session.server().setMessageBus(new DefaultMessageBus());
        this.game = this.session.server().getMessageBus();
        this.access = new ServerScriptAccess(this.session, (message, correlation, world) -> {
            assertInstanceOf(ServerScriptsRequestMessage.class, message);
            if (!this.sends.get()) return false;
            this.sent.add(new Sent(correlation, world));
            return true;
        }, this.admitted::get, (world, refusal) -> this.told.add(world + "|" + refusal), this.runsEnded::incrementAndGet);
    }

    @AfterEach
    void close() {
        this.access.close();
        this.session.close();
    }

    @Test
    void asksOncePerWorldAndTellsTheAnswer() {
        play(FIRST);
        play(FIRST);
        assertEquals(1, this.sent.size(), "the game repeats PLAYING after each handshake, which asks nothing more");
        assertEquals(FIRST.identity(), this.sent.getFirst().world());
        assertEquals("|Waiting for the server", told());

        answer(this.sent.getFirst().correlation(), "");
        assertEquals(FIRST.identity() + "|", told(), "the server runs scripts in that world");
    }

    @Test
    void anOldAnswerChangesNothingAndAnotherWorldEndsTheServerRuns() {
        play(FIRST);
        int first = this.sent.getFirst().correlation();
        play(SECOND);
        assertEquals(1, this.runsEnded.get(), "the runs on the first world's server can no longer report back");

        answer(first, "");
        assertEquals("|Waiting for the server", told(), "the first world's answer came after the game left it");
        answer(this.sent.getLast().correlation(), "Not an operator");
        assertEquals(SECOND.identity() + "|Not an operator", told());
    }

    @Test
    void aRefusedRequestShowsItsReason() {
        play(FIRST);
        refuse(this.sent.getFirst().correlation(), CompanionProtocol.CHANGE, "Not this request");
        assertEquals("|Waiting for the server", told(), "a refusal of another kind of message is not this request's");
        refuse(this.sent.getFirst().correlation(), CompanionProtocol.SERVER_SCRIPTS_REQUEST, "The server does not have TotalDebug");
        assertEquals("|The server does not have TotalDebug", told());
    }

    @Test
    void aRefusalAfterTheGameLeftTheWorldChangesNothing() {
        play(FIRST);
        play(new PlayingPayload.Menu());
        assertEquals("|" + ScriptCompilationService.NO_SERVER, told());

        refuse(this.sent.getFirst().correlation(), CompanionProtocol.SERVER_SCRIPTS_REQUEST, "The server does not have TotalDebug");
        assertEquals("|" + ScriptCompilationService.NO_SERVER, told(), "nothing waits for an answer in the menu");
    }

    @Test
    void aRequestThatCouldNotBeSentIsAskedAgain() {
        this.sends.set(false);
        play(FIRST);
        assertEquals("|Minecraft disconnected", told());
        this.sends.set(true);
        play(FIRST);
        assertEquals(1, this.sent.size(), "the next PLAYING of the same world asks again");

        this.admitted.set(false);
        play(SECOND);
        assertEquals(1, this.sent.size(), "nothing is asked during a project switch");
        assertEquals("|Minecraft disconnected", told());
    }

    @Test
    void aConnectionAsksAnewAndADisconnectLeavesNothingWaiting() {
        play(FIRST);
        int before = this.sent.getFirst().correlation();
        this.access.disconnected();
        answer(before, "");
        assertEquals("|Waiting for the server", told(), "an answer after the disconnect changes nothing");

        this.access.connected(FIRST);
        assertEquals(2, this.sent.size(), "a new connection asks the server the game plays, though it is the same");
        assertEquals("|Waiting for the server", told());
        this.access.connected(null);
        assertEquals("|" + ScriptCompilationService.NO_SERVER, told(), "until the game tells what it plays");
    }

    private void play(PlayingPayload playing) {
        post(new PlayingMessage(playing));
    }

    private void answer(int request, String refusal) {
        post(new ServerScriptsMessage(request, refusal));
    }

    private void refuse(int correlation, short messageId, String reason) {
        post(new RelayFailedMessage(correlation, messageId, reason));
    }

    private void post(AbstractMessage message) {
        this.game.post(message);
    }

    /** What the compiler was told last. */
    private String told() {
        return this.told.getLast();
    }
}
