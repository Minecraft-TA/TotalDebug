package com.github.minecraft_ta.totaldebug.network.script;

import com.github.minecraft_ta.totaldebug.companionApp.messages.script.ExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.companionApp.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.util.mappings.ClassUtil;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

public class RunScriptOnServerMessage implements IMessage, IMessageHandler<RunScriptOnServerMessage, RunScriptOnServerMessage> {

    private int scriptId;
    private String scriptText;
    private ExecutionEnvironment executionEnvironment;

    public RunScriptOnServerMessage() {
    }

    public RunScriptOnServerMessage(int scriptId, String scriptText, ExecutionEnvironment executionEnvironment) {
        this.scriptId = scriptId;
        this.scriptText = scriptText;
        this.executionEnvironment = executionEnvironment;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.scriptId = buf.readInt();
        int length = buf.readInt();
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        this.scriptText = new String(bytes, StandardCharsets.UTF_8);
        this.executionEnvironment = ExecutionEnvironment.valueOf(ByteBufUtils.readUTF8String(buf));
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.scriptId);
        byte[] bytes = this.scriptText.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
        ByteBufUtils.writeUTF8String(buf, this.executionEnvironment.name());
    }

    @Override
    public RunScriptOnServerMessage onMessage(RunScriptOnServerMessage message, MessageContext ctx) {
        ClassUtil.dumpMinecraftClasses();
        ScriptRunner.runScript(message.scriptId, message.scriptText, ctx.getServerHandler().playerEntity, message.executionEnvironment);
        return null;
    }
}
