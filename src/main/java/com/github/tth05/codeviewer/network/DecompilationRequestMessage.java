package com.github.tth05.codeviewer.network;

import com.github.tth05.codeviewer.CodeViewer;
import com.github.tth05.codeviewer.util.ByteBufHelper;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class DecompilationRequestMessage implements IMessage, IMessageHandler<DecompilationRequestMessage, IMessage> {

    public enum HIT_TYPE {
        BLOCK_ENTITY,
        TILE_ENTITY,
        LIVING_ENTITY
    }

    private HIT_TYPE typeofhit;
    private BlockPos pos;
    private int entityID;


    public DecompilationRequestMessage() {
    }

    public DecompilationRequestMessage(HIT_TYPE typeofhit, BlockPos pos, int entityID) {
        this.typeofhit = typeofhit;
        this.pos = pos;
        this.entityID = entityID;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.typeofhit = HIT_TYPE.valueOf(ByteBufHelper.readUTF8String(buf));
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.entityID = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufHelper.writeUTF8String(buf, this.typeofhit.name());
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeInt(this.entityID);
    }

    @Override
    public IMessage onMessage(DecompilationRequestMessage message, MessageContext ctx) {
        EntityPlayerMP player = ctx.getServerHandler().player;
        World world = player.world;

        switch (typeofhit) {
            case BLOCK_ENTITY:
                sendToClient(world.getBlockState(message.pos).getBlock().getClass(), player);
                break;
            case TILE_ENTITY:
                sendToClient(world.getTileEntity(message.pos).getClass(), player);
                break;
            case LIVING_ENTITY:
                sendToClient(world.getEntityByID(message.entityID).getClass(), player);
                break;
        }
        return null;
    }

    public void sendToClient(Class<?> clazz, EntityPlayerMP player) {
        CodeViewer.INSTANCE.decompilationManager.getDecompiledFileContent(clazz).thenAccept(lines ->
                CodeViewer.INSTANCE.network.sendTo(new DecompilationResultMessage(clazz.getName(), lines), player));
    }

}
