package com.github.minecraft_ta.totaldebug.network.script;

import com.github.minecraft_ta.totaldebug.companionApp.script.ScriptRunner;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

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
        if (ScriptRunner.isScriptRunning(message.scriptId, ctx.getServerHandler().player))
            ScriptRunner.stopScript(message.scriptId, ctx.getServerHandler().player);
        return null;
    }
}
