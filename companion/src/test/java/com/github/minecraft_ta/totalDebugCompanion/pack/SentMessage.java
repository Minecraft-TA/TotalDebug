package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessages;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ToServerMessage;
import com.github.tth05.scnet.message.AbstractMessage;

/**
 * A message Companion sent the game: one for the server unwrapped from the relay's envelope, with the world the envelope
 * names, or one for the game client itself, with no world.
 */
record SentMessage(AbstractMessage message, String world) {
    static SentMessage of(AbstractMessage sent) {
        if (sent instanceof ToServerMessage toServer) {
            return new SentMessage(RelayedMessages.decodeToServer(toServer.payload()), toServer.payload().world());
        }
        return new SentMessage(sent, null);
    }
}
