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
        if (Minecraft.getMinecraft().player == null) {
            TotalDebug.PROXY.getCompanionApp().getClient().getMessageProcessor().enqueueMessage(new ScriptStatusMessage(message.scriptId, ScriptStatusMessage.Type.RUN_EXCEPTION, "Script was automatically stopped. Result unknown."));
            return;
        }

        //Stop all scripts of this player
        if (message.scriptId == -1) {
            ScriptRunner.stopAllScripts(Minecraft.getMinecraft().player);
            TotalDebug.INSTANCE.network.sendToServer(new StopScriptOnServerMessage(message.scriptId));
            return;
        }

        //Is script running client side?
        if (ScriptRunner.isScriptRunning(message.scriptId, Minecraft.getMinecraft().player)) {
            ScriptRunner.stopScript(message.scriptId, Minecraft.getMinecraft().player);
        } else {
            TotalDebug.INSTANCE.network.sendToServer(new StopScriptOnServerMessage(message.scriptId));
        }
    }
}
