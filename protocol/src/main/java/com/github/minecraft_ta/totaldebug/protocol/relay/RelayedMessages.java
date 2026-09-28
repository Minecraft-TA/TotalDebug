package com.github.minecraft_ta.totaldebug.protocol.relay;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ManifestRequestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerManifestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerSourceRequestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.StopScriptMessage;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The Companion protocol messages that travel through the relay: to the game's server and back. The server decodes and
 * encodes them with the same classes Companion uses. A new server operation adds its messages here.
 */
public final class RelayedMessages {
    private record Kind(Class<? extends AbstractMessage> type, Supplier<? extends AbstractMessage> create) {
    }

    private static final Map<Short, Kind> TO_SERVER = Map.of(
            CompanionProtocol.RUN_SCRIPT, new Kind(RunScriptMessage.class, RunScriptMessage::new),
            CompanionProtocol.STOP_SCRIPT, new Kind(StopScriptMessage.class, StopScriptMessage::new),
            CompanionProtocol.SERVER_SOURCE_REQUEST, new Kind(ServerSourceRequestMessage.class, ServerSourceRequestMessage::new),
            CompanionProtocol.MANIFEST_REQUEST, new Kind(ManifestRequestMessage.class, ManifestRequestMessage::new));
    private static final Map<Short, Kind> FROM_SERVER = Map.of(
            CompanionProtocol.EXECUTION_RESULT, new Kind(ExecutionResultMessage.class, ExecutionResultMessage::new),
            CompanionProtocol.SERVER_MANIFEST, new Kind(ServerManifestMessage.class, ServerManifestMessage::new));

    private RelayedMessages() {
    }

    /** {@code message}, for the server, with the request's correlation and the game session it is valid in. */
    public static RelayedMessage toServer(AbstractMessage message, int correlation, String gameSession) {
        return new RelayedMessage(correlation, gameSession, id(TO_SERVER, message), encode(message));
    }

    /** {@code message}, from the server, for Companion. */
    public static RelayedMessage fromServer(AbstractMessage message) {
        return new RelayedMessage(0, "", id(FROM_SERVER, message), encode(message));
    }

    /** The message the server receives; refuses an id the server does not take. */
    public static AbstractMessage decodeToServer(RelayedMessage relayed) {
        return decode(TO_SERVER, relayed);
    }

    /** The message Companion receives; refuses an id the server does not send. */
    public static AbstractMessage decodeFromServer(RelayedMessage relayed) {
        return decode(FROM_SERVER, relayed);
    }

    private static short id(Map<Short, Kind> kinds, AbstractMessage message) {
        for (Map.Entry<Short, Kind> kind : kinds.entrySet()) {
            if (kind.getValue().type() == message.getClass()) return kind.getKey();
        }
        throw new IllegalArgumentException(message.getClass().getSimpleName() + " does not travel through the relay this way");
    }

    private static byte[] encode(AbstractMessage message) {
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        message.write(output);
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static AbstractMessage decode(Map<Short, Kind> kinds, RelayedMessage relayed) {
        Kind kind = kinds.get(relayed.messageId());
        if (kind == null) throw new IllegalArgumentException("Message " + relayed.messageId() + " does not travel through the relay this way");
        AbstractMessage message = kind.create().get();
        ByteBuffer body = ByteBuffer.wrap(relayed.body());
        message.read(new ByteBufferInputStream(body));
        if (body.hasRemaining()) throw new IllegalArgumentException("Message " + relayed.messageId() + " has bytes after its end");
        return message;
    }
}
