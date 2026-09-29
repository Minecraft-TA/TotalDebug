package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.tth05.scnet.message.IMessageProcessor;

/** The exact allowed message directions at each protocol endpoint. */
public final class ProtocolBindings {
    private ProtocolBindings() {
    }

    public static void registerMod(IMessageProcessor processor) {
        processor.registerIncoming(CompanionProtocol.READY, ReadyMessage.class, ReadyMessage::new);
        processor.registerIncoming(CompanionProtocol.RUN_SCRIPT, RunScriptMessage.class, RunScriptMessage::new);
        processor.registerOutgoing(CompanionProtocol.EXECUTION_RESULT, ExecutionResultMessage.class);
        processor.registerIncoming(CompanionProtocol.STOP_SCRIPT, StopScriptMessage.class, StopScriptMessage::new);
        processor.registerOutgoing(CompanionProtocol.FOCUS_WINDOW, FocusWindowMessage.class);
        processor.registerOutgoing(CompanionProtocol.CLIENT_HELLO, ClientHelloMessage.class);
        processor.registerIncoming(CompanionProtocol.SERVER_HELLO, ServerHelloMessage.class, ServerHelloMessage::new);
        processor.registerIncoming(CompanionProtocol.RETRY_RUNTIME_INVENTORY, RetryRuntimeInventoryMessage.class, RetryRuntimeInventoryMessage::new);
        processor.registerOutgoing(CompanionProtocol.DEBUG_TARGET, DebugTargetMessage.class);
        processor.registerOutgoing(CompanionProtocol.INSPECT_SUBJECT, InspectSubjectMessage.class);
        processor.registerOutgoing(CompanionProtocol.PACK_STACK, PackStackMessage.class);
        processor.registerIncoming(CompanionProtocol.RELOAD, ReloadMessage.class, ReloadMessage::new);
        processor.registerOutgoing(CompanionProtocol.RELOAD_RESULT, ReloadResultMessage.class);
        processor.registerOutgoing(CompanionProtocol.PLAYING, PlayingMessage.class);
        processor.registerIncoming(CompanionProtocol.TO_SERVER, ToServerMessage.class, ToServerMessage::new);
        processor.registerOutgoing(CompanionProtocol.FROM_SERVER, FromServerMessage.class);
        processor.registerOutgoing(CompanionProtocol.RELAY_FAILED, RelayFailedMessage.class);
        processor.registerOutgoing(CompanionProtocol.PREPARED_FILE, PreparedFileMessage.class);
        processor.registerIncoming(CompanionProtocol.CHANGE, ChangeMessage.class, ChangeMessage::new);
        processor.registerOutgoing(CompanionProtocol.CHANGE_RESULT, ChangeResultMessage.class);
    }

    public static void registerCompanion(IMessageProcessor processor) {
        processor.registerOutgoing(CompanionProtocol.READY, ReadyMessage.class);
        processor.registerOutgoing(CompanionProtocol.RUN_SCRIPT, RunScriptMessage.class);
        processor.registerIncoming(CompanionProtocol.EXECUTION_RESULT, ExecutionResultMessage.class, ExecutionResultMessage::new);
        processor.registerOutgoing(CompanionProtocol.STOP_SCRIPT, StopScriptMessage.class);
        processor.registerIncoming(CompanionProtocol.FOCUS_WINDOW, FocusWindowMessage.class, FocusWindowMessage::new);
        processor.registerIncoming(CompanionProtocol.CLIENT_HELLO, ClientHelloMessage.class, ClientHelloMessage::new);
        processor.registerOutgoing(CompanionProtocol.SERVER_HELLO, ServerHelloMessage.class);
        processor.registerOutgoing(CompanionProtocol.RETRY_RUNTIME_INVENTORY, RetryRuntimeInventoryMessage.class);
        processor.registerIncoming(CompanionProtocol.DEBUG_TARGET, DebugTargetMessage.class, DebugTargetMessage::new);
        processor.registerIncoming(CompanionProtocol.INSPECT_SUBJECT, InspectSubjectMessage.class, InspectSubjectMessage::new);
        processor.registerIncoming(CompanionProtocol.PACK_STACK, PackStackMessage.class, PackStackMessage::new);
        processor.registerOutgoing(CompanionProtocol.RELOAD, ReloadMessage.class);
        processor.registerIncoming(CompanionProtocol.RELOAD_RESULT, ReloadResultMessage.class, ReloadResultMessage::new);
        processor.registerIncoming(CompanionProtocol.PLAYING, PlayingMessage.class, PlayingMessage::new);
        processor.registerOutgoing(CompanionProtocol.TO_SERVER, ToServerMessage.class);
        processor.registerIncoming(CompanionProtocol.FROM_SERVER, FromServerMessage.class, FromServerMessage::new);
        processor.registerIncoming(CompanionProtocol.RELAY_FAILED, RelayFailedMessage.class, RelayFailedMessage::new);
        processor.registerIncoming(CompanionProtocol.PREPARED_FILE, PreparedFileMessage.class, PreparedFileMessage::new);
        processor.registerOutgoing(CompanionProtocol.CHANGE, ChangeMessage.class);
        processor.registerIncoming(CompanionProtocol.CHANGE_RESULT, ChangeResultMessage.class, ChangeResultMessage::new);
    }
}
