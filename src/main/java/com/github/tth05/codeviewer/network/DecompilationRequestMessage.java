package com.github.tth05.codeviewer.network;

import com.github.tth05.codeviewer.CodeViewer;
import com.github.tth05.codeviewer.HitType;
import com.github.tth05.codeviewer.util.ByteBufHelper;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class DecompilationRequestMessage implements IMessage, IMessageHandler<DecompilationRequestMessage, IMessage> {


    private HitType typeOfHit;
    private BlockPos pos;
    private int entityId;


    public DecompilationRequestMessage() {
    }

    public DecompilationRequestMessage(HitType typeofhit, BlockPos pos) {
        this.typeOfHit = typeofhit;
        this.pos = pos;
        this.entityId = -1;
    }

    public DecompilationRequestMessage(HitType typeOfHit, int entityId) {
        this.typeOfHit = typeOfHit;
        this.entityId = entityId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.typeOfHit = HitType.values()[buf.readInt()];
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.entityId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.typeOfHit.ordinal());
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeInt(this.entityId);
    }

    @Override
    public IMessage onMessage(DecompilationRequestMessage message, MessageContext ctx) {
        EntityPlayerMP player = ctx.getServerHandler().player;
        World world = player.world;

        ForgeChunkManager.getPersistentChunksFor(world);

        switch (typeOfHit) {
            case BLOCK_ENTITY:
                sendToClient(world.getBlockState(message.pos).getBlock().getClass(), player);
                break;
            case TILE_ENTITY:
                TileEntity tileEntity = world.getTileEntity(message.pos);
                if (tileEntity != null) {
                    sendToClient(tileEntity.getClass(), player);
                } else {
                    CodeViewer.LOGGER.error("TileEntity is null");
                }
                break;
            case LIVING_ENTITY:
                Entity entity = world.getEntityByID(message.entityId);
                if (entity != null) {
                    sendToClient(entity.getClass(), player);
                } else {
                    CodeViewer.LOGGER.error("Entity is null");
                }
                break;
        }
        return null;
    }

    public void sendToClient(Class<?> clazz, EntityPlayerMP player) {
        CodeViewer.INSTANCE.decompilationManager.getDecompiledFileContent(clazz).thenAccept(lines ->
                CodeViewer.INSTANCE.network.sendTo(new DecompilationResultMessage(clazz.getName(), lines), player));
    }
}
