package com.github.minecraft_ta.totaldebug.network.script;

import com.github.minecraft_ta.totaldebug.companionApp.script.ScriptRunner;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.nio.charset.StandardCharsets;

public class RunScriptOnServerMessage implements IMessage, IMessageHandler<RunScriptOnServerMessage, RunScriptOnServerMessage> {

    private int scriptId;
    private String scriptText;

    public RunScriptOnServerMessage() {}

    public RunScriptOnServerMessage(int scriptId, String scriptText) {
        this.scriptId = scriptId;
        this.scriptText = scriptText;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.scriptId = buf.readInt();
        int length = buf.readInt();
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        this.scriptText = new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.scriptId);
        byte[] bytes = this.scriptText.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    @Override
    public RunScriptOnServerMessage onMessage(RunScriptOnServerMessage message, MessageContext ctx) {
        ScriptRunner.runScript(message.scriptId, message.scriptText, ctx.getServerHandler().player);
        return null;
    }
}
