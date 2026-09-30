package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.tth05.scnet.message.AbstractMessage;

import java.util.function.Consumer;

/**
 * Where the game's messages are delivered (docs/SYSTEMS.md, section 6): an owner registers the messages it handles, and
 * removes them when it closes, as a project that is no longer the current one.
 */
public interface MessageRoutes {
    /**
     * Runs {@code handler} for each message of {@code type} from the game, also one the server sent through it, on the
     * connection's thread in arrival order; returns what removes it.
     */
    <M extends AbstractMessage> Runnable on(Class<M> type, Consumer<M> handler);
}
