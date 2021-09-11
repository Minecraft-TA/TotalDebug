package com.github.minecraft_ta.totaldebug.network.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.messages.script.ScriptStatusMessage;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.nio.charset.StandardCharsets;

public class ServerScriptStatusMessage implements IMessage, IMessageHandler<ServerScriptStatusMessage, ServerScriptStatusMessage> {

    private int scriptId;
    private ScriptStatusMessage.Type type;
    private String message;

    public ServerScriptStatusMessage() {
    }

    public ServerScriptStatusMessage(int scriptId, ScriptStatusMessage.Type type, String scriptText) {
        this.scriptId = scriptId;
        this.type = type;
        this.message = scriptText;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.scriptId = buf.readInt();
        this.type = ScriptStatusMessage.Type.valueOf(ByteBufUtils.readUTF8String(buf));
        int length = buf.readInt();
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        this.message = new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.scriptId);
        ByteBufUtils.writeUTF8String(buf, this.type.name());
        byte[] bytes = this.message.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    @Override
    public ServerScriptStatusMessage onMessage(ServerScriptStatusMessage message, MessageContext ctx) {
        TotalDebug.PROXY.getCompanionApp().getClient().getMessageProcessor().enqueueMessage(new ScriptStatusMessage(message.scriptId, message.type, message.message));
        return null;
    }
}
