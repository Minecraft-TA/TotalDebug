package com.github.minecraft_ta.totaldebug.network.script;

import com.github.minecraft_ta.totaldebug.companionApp.script.ScriptRunner;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class StopScriptOnServerMessage implements IMessage, IMessageHandler<StopScriptOnServerMessage, StopScriptOnServerMessage> {

    private int scriptId;

    public StopScriptOnServerMessage() {
    }

    public StopScriptOnServerMessage(int scriptId) {
        this.scriptId = scriptId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.scriptId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.scriptId);
    }

    @Override
    public StopScriptOnServerMessage onMessage(StopScriptOnServerMessage message, MessageContext ctx) {
        if (message.scriptId == -1) {
            ScriptRunner.stopAllScripts(ctx.getServerHandler().playerEntity);
            return null;
        }

        if (ScriptRunner.isScriptRunning(message.scriptId, ctx.getServerHandler().playerEntity))
            ScriptRunner.stopScript(message.scriptId, ctx.getServerHandler().playerEntity);
        return null;
    }
}
