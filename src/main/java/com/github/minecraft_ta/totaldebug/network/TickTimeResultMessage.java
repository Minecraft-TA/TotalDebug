package com.github.minecraft_ta.totaldebug.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class TickTimeResultMessage implements IMessage, IMessageHandler<TickTimeResultMessage, IMessage> {

    private double mspt;
    private double tps;

    public TickTimeResultMessage() {
    }

    public TickTimeResultMessage(double mspt, double tps) {
        this.mspt = mspt;
        this.tps = tps;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.mspt = buf.readDouble();
        this.tps = buf.readDouble();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeDouble(this.mspt);
        buf.writeDouble(this.tps);
    }

    @Override
    public IMessage onMessage(TickTimeResultMessage message, MessageContext ctx) {
        GuiPlayerTabOverlay tabList = Minecraft.getMinecraft().ingameGUI.getTabList();
        tabList.setHeader(new TextComponentString(TextFormatting.DARK_PURPLE + "MSPT: " + TextFormatting.WHITE + message.mspt + TextFormatting.DARK_AQUA + " TPS: " + TextFormatting.WHITE + message.tps));

        return null;
    }
}
