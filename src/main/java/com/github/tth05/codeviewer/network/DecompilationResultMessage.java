package com.github.tth05.codeviewer.network;

import com.github.tth05.codeviewer.gui.CodeViewScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

public class DecompilationResultMessage implements IMessage, IMessageHandler<DecompilationResultMessage, IMessage> {

    private String filename;
    private List<String> lines;

    public DecompilationResultMessage() {}

    public DecompilationResultMessage(String filename, List<String> lines) {
        this.filename = filename;
        this.lines = lines;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.filename = ByteBufUtils.readUTF8String(buf);

        int s = buf.readInt();
        lines = new ArrayList<>(s);
        for (int i = 0; i < s; i++) {
            lines.add(ByteBufUtils.readUTF8String(buf));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.filename);

        buf.writeInt(this.lines.size());
        for (String line : this.lines) {
            ByteBufUtils.writeUTF8String(buf, line);
        }
    }

    @Override
    public IMessage onMessage(DecompilationResultMessage message, MessageContext ctx) {
        GuiScreen currentScreen = Minecraft.getMinecraft().currentScreen;
        if (currentScreen instanceof CodeViewScreen) {
           ((CodeViewScreen) currentScreen).setLines(message.lines);
           ((CodeViewScreen) currentScreen).setFilename(message.filename);
        }
        return null;
    }
}
