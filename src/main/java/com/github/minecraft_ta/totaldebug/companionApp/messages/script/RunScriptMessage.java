package com.github.minecraft_ta.totaldebug.companionApp.messages.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.network.script.RunScriptOnServerMessage;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public class RunScriptMessage extends AbstractMessageIncoming {

    private int scriptId;
    private String scriptText;
    private boolean serverSide;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.scriptId = messageStream.readInt();
        this.scriptText = messageStream.readString();
        this.serverSide = messageStream.readBoolean();
    }

    public static void handle(RunScriptMessage message) {
        if (message.serverSide) {
            TotalDebug.INSTANCE.network.sendToServer(new RunScriptOnServerMessage(message.scriptId, message.scriptText));
        } else {
            ScriptRunner.runScript(message.scriptId, message.scriptText, null);
        }
    }
}
