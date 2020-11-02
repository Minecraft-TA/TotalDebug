package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.gui.codeviewer.CodeViewScreen;
import com.github.minecraft_ta.totaldebug.util.ByteBufHelper;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class DecompilationResultMessage implements IMessage, IMessageHandler<DecompilationResultMessage, IMessage> {

    private String filename;
    private String text;

    public DecompilationResultMessage() {
    }

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
        CodeViewScreen codeViewScreen = new CodeViewScreen();
        FMLClientHandler.instance().showGuiScreen(codeViewScreen);
        codeViewScreen.setJavaCode(message.text);
        return null;
    }
}
