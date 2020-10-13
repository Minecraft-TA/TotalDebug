package com.github.tth05.codeviewer.network;

import com.github.tth05.codeviewer.gui.CodeViewScreen;
import com.github.tth05.codeviewer.util.ByteBufHelper;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class DecompilationResultMessage implements IMessage, IMessageHandler<DecompilationResultMessage, IMessage> {

    private String filename;
    private String text;

    public DecompilationResultMessage() {}

    public DecompilationResultMessage(String filename, String text) {
        this.filename = filename;
        this.text = text;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.filename = ByteBufUtils.readUTF8String(buf);

        this.text = ByteBufHelper.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.filename);

        ByteBufHelper.writeUTF8String(buf, this.text);
    }

    @Override
    public IMessage onMessage(DecompilationResultMessage message, MessageContext ctx) {
        GuiScreen currentScreen = Minecraft.getMinecraft().currentScreen;
        if (currentScreen instanceof CodeViewScreen) {
           ((CodeViewScreen) currentScreen).setJavaCode(message.text);
        }
        return null;
    }
}
