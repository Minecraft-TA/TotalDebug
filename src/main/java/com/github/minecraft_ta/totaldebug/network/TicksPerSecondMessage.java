package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.block.tile.TickBlockTile;
import com.github.minecraft_ta.totaldebug.util.BlockPos;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

public class TicksPerSecondMessage implements IMessage, IMessageHandler<TicksPerSecondMessage, IMessage> {

    private int tps;
    private int average;
    private BlockPos pos;

    public TicksPerSecondMessage() {
    }

    public TicksPerSecondMessage(BlockPos pos, int tps, int average) {
        this.pos = pos;
        this.tps = tps;
        this.average = average;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.tps = buf.readInt();
        this.average = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeInt(this.tps);
        buf.writeInt(this.average);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public IMessage onMessage(TicksPerSecondMessage message, MessageContext ctx) {
        World world = Minecraft.getMinecraft().theWorld;

        if (world == null || message.pos == null)
            return null;

        Minecraft.getMinecraft().func_152343_a(() -> {
            BlockPos blockPos = message.pos;
            if (!world.isBlockLoaded(blockPos))
                return;

            TileEntity te = world.getTileEntity(blockPos.getX(), blockPos.getY(), blockPos.getZ());
            if (!(te instanceof TickBlockTile))
                return;

            ((TickBlockTile) te).updateData(message.average, message.tps);
        });

        return null;
    }
}
