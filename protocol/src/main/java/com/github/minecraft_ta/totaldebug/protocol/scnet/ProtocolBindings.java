package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.tth05.scnet.message.IMessageProcessor;

/** The exact allowed message directions at each protocol endpoint. */
public final class ProtocolBindings {
    private ProtocolBindings() {
    }

    public static void registerMod(IMessageProcessor processor) {
        processor.registerIncoming(CompanionProtocol.READY, ReadyMessage.class, ReadyMessage::new);
        processor.registerBidirectional(CompanionProtocol.OPEN_CLASS, OpenClassMessage.class, OpenClassMessage::new);
        processor.registerIncoming(CompanionProtocol.RUN_SCRIPT, RunScriptMessage.class, RunScriptMessage::new);
        processor.registerOutgoing(CompanionProtocol.EXECUTION_RESULT, ExecutionResultMessage.class);
        processor.registerIncoming(CompanionProtocol.STOP_SCRIPT, StopScriptMessage.class, StopScriptMessage::new);
        processor.registerOutgoing(CompanionProtocol.FOCUS_WINDOW, FocusWindowMessage.class);
        processor.registerOutgoing(CompanionProtocol.CLIENT_HELLO, ClientHelloMessage.class);
        processor.registerIncoming(CompanionProtocol.SERVER_HELLO, ServerHelloMessage.class, ServerHelloMessage::new);
        processor.registerOutgoing(CompanionProtocol.RUNTIME_INVENTORY, RuntimeInventoryMessage.class);
        processor.registerIncoming(CompanionProtocol.RETRY_RUNTIME_INVENTORY, RetryRuntimeInventoryMessage.class, RetryRuntimeInventoryMessage::new);
        processor.registerOutgoing(CompanionProtocol.DEBUG_TARGET, DebugTargetMessage.class);
        processor.registerOutgoing(CompanionProtocol.SERVER_MANIFEST, ServerManifestMessage.class);
    }

    public static void registerCompanion(IMessageProcessor processor) {
        processor.registerOutgoing(CompanionProtocol.READY, ReadyMessage.class);
        processor.registerBidirectional(CompanionProtocol.OPEN_CLASS, OpenClassMessage.class, OpenClassMessage::new);
        processor.registerOutgoing(CompanionProtocol.RUN_SCRIPT, RunScriptMessage.class);
        processor.registerIncoming(CompanionProtocol.EXECUTION_RESULT, ExecutionResultMessage.class, ExecutionResultMessage::new);
        processor.registerOutgoing(CompanionProtocol.STOP_SCRIPT, StopScriptMessage.class);
        processor.registerIncoming(CompanionProtocol.FOCUS_WINDOW, FocusWindowMessage.class, FocusWindowMessage::new);
        processor.registerIncoming(CompanionProtocol.CLIENT_HELLO, ClientHelloMessage.class, ClientHelloMessage::new);
        processor.registerOutgoing(CompanionProtocol.SERVER_HELLO, ServerHelloMessage.class);
        processor.registerIncoming(CompanionProtocol.RUNTIME_INVENTORY, RuntimeInventoryMessage.class, RuntimeInventoryMessage::new);
        processor.registerOutgoing(CompanionProtocol.RETRY_RUNTIME_INVENTORY, RetryRuntimeInventoryMessage.class);
        processor.registerIncoming(CompanionProtocol.DEBUG_TARGET, DebugTargetMessage.class, DebugTargetMessage::new);
        processor.registerIncoming(CompanionProtocol.SERVER_MANIFEST, ServerManifestMessage.class, ServerManifestMessage::new);
    }
}
