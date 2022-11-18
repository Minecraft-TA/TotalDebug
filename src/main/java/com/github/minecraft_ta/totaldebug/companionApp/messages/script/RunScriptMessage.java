package com.github.minecraft_ta.totaldebug.companionApp.messages.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.network.script.RunScriptOnServerMessage;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import net.minecraft.client.Minecraft;

public class RunScriptMessage extends AbstractMessageIncoming {

    private int scriptId;
    private String scriptText;
    private boolean serverSide;
    private ExecutionEnvironment executionEnvironment;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.scriptId = messageStream.readInt();
        this.scriptText = messageStream.readString();
        this.serverSide = messageStream.readBoolean();
        this.executionEnvironment = ExecutionEnvironment.valueOf(messageStream.readString());
    }

    public static void handle(RunScriptMessage message) {
        if (Minecraft.getMinecraft().thePlayer == null) {
            TotalDebug.PROXY.getCompanionApp().getClient().getMessageProcessor().enqueueMessage(new ScriptStatusMessage(message.scriptId, ScriptStatusMessage.Type.COMPILATION_FAILED, "Join a world to run scripts!"));
            return;
        }
        if (message.serverSide) {
            TotalDebug.INSTANCE.network.sendToServer(new RunScriptOnServerMessage(message.scriptId, message.scriptText, message.executionEnvironment));
        } else {
            ScriptRunner.runScript(message.scriptId, message.scriptText, Minecraft.getMinecraft().thePlayer, message.executionEnvironment);
        }
    }
}
