package com.github.minecraft_ta.totaldebug.companionApp.messages.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.network.script.StopScriptOnServerMessage;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import net.minecraft.client.Minecraft;

public class StopScriptMessage extends AbstractMessageIncoming {

    private int scriptId;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.scriptId = messageStream.readInt();
    }

    public static void handle(StopScriptMessage message) {
        //Is script running client side?
        if (ScriptRunner.isScriptRunning(message.scriptId, Minecraft.getMinecraft().player)) {
            ScriptRunner.stopScript(message.scriptId, Minecraft.getMinecraft().player);
        } else {
            TotalDebug.INSTANCE.network.sendToServer(new StopScriptOnServerMessage(message.scriptId));
        }
    }
}
