package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.message.IMessageProcessor;

import java.util.List;
import java.util.function.Supplier;

/**
 * Every message of the protocol, once: its id, how it is made, which way it goes between the game and Companion, and
 * which way it goes through the server relay. Each endpoint registers exactly the directions it may receive and send,
 * and {@link com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessages} carries the relayed ones. A new message
 * adds one line here, beside its id in {@link CompanionProtocol}.
 */
public final class ProtocolBindings {
    /** Which way a message goes on the connection between the game and Companion. */
    public enum Direct { TO_GAME, TO_COMPANION, NONE }

    /** Which way a message goes through the relay between the game's client and its server. */
    public enum Relay { TO_SERVER, FROM_SERVER, NONE }

    public record Binding(short id, Class<? extends AbstractMessage> type, Supplier<? extends AbstractMessage> create,
                          Direct direct, Relay relay) {
    }

    public static final List<Binding> ALL = List.of(
            // The session
            new Binding(CompanionProtocol.READY, ReadyMessage.class, ReadyMessage::new, Direct.TO_GAME, Relay.NONE),
            new Binding(CompanionProtocol.CLIENT_HELLO, ClientHelloMessage.class, ClientHelloMessage::new, Direct.TO_COMPANION, Relay.NONE),
            new Binding(CompanionProtocol.SERVER_HELLO, ServerHelloMessage.class, ServerHelloMessage::new, Direct.TO_GAME, Relay.NONE),
            new Binding(CompanionProtocol.DEBUG_TARGET, DebugTargetMessage.class, DebugTargetMessage::new, Direct.TO_COMPANION, Relay.NONE),
            new Binding(CompanionProtocol.PREPARED_FILE, PreparedFileMessage.class, PreparedFileMessage::new, Direct.TO_COMPANION, Relay.NONE),
            new Binding(CompanionProtocol.RETRY_RUNTIME_INVENTORY, RetryRuntimeInventoryMessage.class, RetryRuntimeInventoryMessage::new, Direct.TO_GAME, Relay.NONE),
            // Inspection
            new Binding(CompanionProtocol.FOCUS_WINDOW, FocusWindowMessage.class, FocusWindowMessage::new, Direct.TO_COMPANION, Relay.NONE),
            new Binding(CompanionProtocol.INSPECT_SUBJECT, InspectSubjectMessage.class, InspectSubjectMessage::new, Direct.TO_COMPANION, Relay.NONE),
            // Scripts
            new Binding(CompanionProtocol.RUN_SCRIPT, RunScriptMessage.class, RunScriptMessage::new, Direct.TO_GAME, Relay.TO_SERVER),
            new Binding(CompanionProtocol.STOP_SCRIPT, StopScriptMessage.class, StopScriptMessage::new, Direct.TO_GAME, Relay.TO_SERVER),
            new Binding(CompanionProtocol.EXECUTION_RESULT, ExecutionResultMessage.class, ExecutionResultMessage::new, Direct.TO_COMPANION, Relay.FROM_SERVER),
            new Binding(CompanionProtocol.SERVER_SCRIPTS_REQUEST, ServerScriptsRequestMessage.class, ServerScriptsRequestMessage::new, Direct.NONE, Relay.TO_SERVER),
            new Binding(CompanionProtocol.SERVER_SCRIPTS, ServerScriptsMessage.class, ServerScriptsMessage::new, Direct.NONE, Relay.FROM_SERVER),
            // The relay
            new Binding(CompanionProtocol.TO_SERVER, ToServerMessage.class, ToServerMessage::new, Direct.TO_GAME, Relay.NONE),
            new Binding(CompanionProtocol.FROM_SERVER, FromServerMessage.class, FromServerMessage::new, Direct.TO_COMPANION, Relay.NONE),
            new Binding(CompanionProtocol.RELAY_FAILED, RelayFailedMessage.class, RelayFailedMessage::new, Direct.TO_COMPANION, Relay.NONE),
            new Binding(CompanionProtocol.COMPANION_LEFT, CompanionLeftMessage.class, CompanionLeftMessage::new, Direct.NONE, Relay.TO_SERVER),
            // Changes and reloads
            new Binding(CompanionProtocol.CHANGE, ChangeMessage.class, ChangeMessage::new, Direct.TO_GAME, Relay.TO_SERVER),
            new Binding(CompanionProtocol.CHANGE_RESULT, ChangeResultMessage.class, ChangeResultMessage::new, Direct.TO_COMPANION, Relay.FROM_SERVER),
            new Binding(CompanionProtocol.RELOAD, ReloadMessage.class, ReloadMessage::new, Direct.TO_GAME, Relay.TO_SERVER),
            new Binding(CompanionProtocol.RELOAD_RESULT, ReloadResultMessage.class, ReloadResultMessage::new, Direct.TO_COMPANION, Relay.FROM_SERVER),
            // Packs and the world
            new Binding(CompanionProtocol.PACK_STACK, PackStackMessage.class, PackStackMessage::new, Direct.TO_COMPANION, Relay.NONE),
            new Binding(CompanionProtocol.PLAYING, PlayingMessage.class, PlayingMessage::new, Direct.TO_COMPANION, Relay.NONE),
            new Binding(CompanionProtocol.DATAPACKS_REQUEST, DatapacksRequestMessage.class, DatapacksRequestMessage::new, Direct.NONE, Relay.TO_SERVER),
            new Binding(CompanionProtocol.DATAPACKS, DatapacksMessage.class, DatapacksMessage::new, Direct.NONE, Relay.FROM_SERVER),
            // Key bindings
            new Binding(CompanionProtocol.KEY_ASSIGNMENTS, KeyAssignmentsMessage.class, KeyAssignmentsMessage::new, Direct.TO_COMPANION, Relay.NONE));

    private ProtocolBindings() {
    }

    /** The game's endpoint: it receives what goes to the game and sends what goes to Companion. */
    public static void registerMod(IMessageProcessor processor) {
        register(processor, Direct.TO_GAME);
    }

    /** Companion's endpoint: it receives what goes to Companion and sends what goes to the game. */
    public static void registerCompanion(IMessageProcessor processor) {
        register(processor, Direct.TO_COMPANION);
    }

    /** The messages the game receives from Companion directly, for the game's request handlers. */
    public static List<Class<? extends AbstractMessage>> toGame() {
        return ALL.stream().filter(binding -> binding.direct() == Direct.TO_GAME).<Class<? extends AbstractMessage>>map(Binding::type).toList();
    }

    private static void register(IMessageProcessor processor, Direct incoming) {
        for (Binding binding : ALL) {
            if (binding.direct() == Direct.NONE) continue;
            if (binding.direct() == incoming) incoming(processor, binding);
            else processor.registerOutgoing(binding.id(), binding.type());
        }
    }

    @SuppressWarnings("unchecked")
    private static <M extends AbstractMessage> void incoming(IMessageProcessor processor, Binding binding) {
        processor.registerIncoming(binding.id(), (Class<M>) binding.type(), (Supplier<M>) binding.create());
    }
}
