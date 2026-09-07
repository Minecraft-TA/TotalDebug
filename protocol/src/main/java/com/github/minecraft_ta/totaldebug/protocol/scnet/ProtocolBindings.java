package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.tth05.scnet.message.IMessageProcessor;

/** The exact allowed message directions at each protocol endpoint. */
public final class ProtocolBindings {
    private ProtocolBindings() {}
    public static void registerMod(IMessageProcessor processor) {
        processor.registerMessage(
                CompanionProtocol.READY,
                com.github.minecraft_ta.totaldebug.protocol.scnet.mod.CompanionReadyMessage.class,
                com.github.minecraft_ta.totaldebug.protocol.scnet.mod.CompanionReadyMessage::new
        );
        processor.registerMessage(
                CompanionProtocol.OPEN_CLASS,
                com.github.minecraft_ta.totaldebug.protocol.scnet.OpenClassMessage.class,
                com.github.minecraft_ta.totaldebug.protocol.scnet.OpenClassMessage::new
        );
        processor.registerMessage(
                CompanionProtocol.RUN_SCRIPT,
                com.github.minecraft_ta.totaldebug.protocol.scnet.mod.RunScriptMessage.class,
                com.github.minecraft_ta.totaldebug.protocol.scnet.mod.RunScriptMessage::new
        );
        processor.registerMessage(
                CompanionProtocol.EXECUTION_RESULT,
                com.github.minecraft_ta.totaldebug.protocol.scnet.mod.ExecutionResultMessage.class
        );
        processor.registerMessage(
                CompanionProtocol.STOP_SCRIPT,
                com.github.minecraft_ta.totaldebug.protocol.scnet.mod.StopScriptMessage.class,
                com.github.minecraft_ta.totaldebug.protocol.scnet.mod.StopScriptMessage::new
        );
        processor.registerMessage(CompanionProtocol.FOCUS_WINDOW, com.github.minecraft_ta.totaldebug.protocol.scnet.mod.FocusWindowMessage.class);
        processor.registerMessage(CompanionProtocol.CLIENT_HELLO, com.github.minecraft_ta.totaldebug.protocol.scnet.mod.ClientHelloMessage.class);
        processor.registerMessage(
                CompanionProtocol.SERVER_HELLO,
                com.github.minecraft_ta.totaldebug.protocol.scnet.mod.ServerHelloMessage.class,
                com.github.minecraft_ta.totaldebug.protocol.scnet.mod.ServerHelloMessage::new
        );
        processor.registerMessage(
                CompanionProtocol.RUNTIME_INVENTORY,
                com.github.minecraft_ta.totaldebug.protocol.scnet.mod.RuntimeInventoryMessage.class
        );
        processor.registerMessage(
                CompanionProtocol.RETRY_RUNTIME_INVENTORY,
                com.github.minecraft_ta.totaldebug.protocol.scnet.mod.RetryRuntimeInventoryMessage.class,
                com.github.minecraft_ta.totaldebug.protocol.scnet.mod.RetryRuntimeInventoryMessage::new
        );
        processor.registerMessage(
                CompanionProtocol.DEBUG_TARGET,
                com.github.minecraft_ta.totaldebug.protocol.scnet.mod.DebugTargetMessage.class
        );

    }
    public static void registerCompanion(IMessageProcessor processor) {
        processor.registerMessage(CompanionProtocol.READY, com.github.minecraft_ta.totaldebug.protocol.scnet.companion.ReadyMessage.class);
        processor.registerMessage(
                CompanionProtocol.OPEN_CLASS,
                com.github.minecraft_ta.totaldebug.protocol.scnet.OpenClassMessage.class,
                com.github.minecraft_ta.totaldebug.protocol.scnet.OpenClassMessage::new
        );
        processor.registerMessage(CompanionProtocol.RUN_SCRIPT, com.github.minecraft_ta.totaldebug.protocol.scnet.companion.RunScriptMessage.class);
        processor.registerMessage(
                CompanionProtocol.EXECUTION_RESULT,
                com.github.minecraft_ta.totaldebug.protocol.scnet.companion.ExecutionResultMessage.class,
                com.github.minecraft_ta.totaldebug.protocol.scnet.companion.ExecutionResultMessage::new
        );
        processor.registerMessage(CompanionProtocol.STOP_SCRIPT, com.github.minecraft_ta.totaldebug.protocol.scnet.companion.StopScriptMessage.class);
        processor.registerMessage(CompanionProtocol.FOCUS_WINDOW, com.github.minecraft_ta.totaldebug.protocol.scnet.companion.FocusWindowMessage.class, com.github.minecraft_ta.totaldebug.protocol.scnet.companion.FocusWindowMessage::new);
        processor.registerMessage(CompanionProtocol.CLIENT_HELLO, com.github.minecraft_ta.totaldebug.protocol.scnet.companion.ClientHelloMessage.class, com.github.minecraft_ta.totaldebug.protocol.scnet.companion.ClientHelloMessage::new);
        processor.registerMessage(CompanionProtocol.SERVER_HELLO, com.github.minecraft_ta.totaldebug.protocol.scnet.companion.ServerHelloMessage.class);
        processor.registerMessage(
                CompanionProtocol.RUNTIME_INVENTORY,
                com.github.minecraft_ta.totaldebug.protocol.scnet.companion.RuntimeInventoryMessage.class,
                com.github.minecraft_ta.totaldebug.protocol.scnet.companion.RuntimeInventoryMessage::new
        );
        processor.registerMessage(
                CompanionProtocol.RETRY_RUNTIME_INVENTORY,
                com.github.minecraft_ta.totaldebug.protocol.scnet.companion.RetryRuntimeInventoryMessage.class
        );
        processor.registerMessage(
                CompanionProtocol.DEBUG_TARGET,
                com.github.minecraft_ta.totaldebug.protocol.scnet.companion.DebugTargetMessage.class,
                com.github.minecraft_ta.totaldebug.protocol.scnet.companion.DebugTargetMessage::new
        );
    }
}
