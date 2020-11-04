package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.HitType;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DecompilationRequestMessage implements IMessage, IMessageHandler<DecompilationRequestMessage, IMessage> {

    private static final Map<UUID, Long> COOLDOWN_MAP = new HashMap<>();

    private HitType typeOfHit;
    private BlockPos pos;
    private int entityOrItemId;

    public DecompilationRequestMessage() {
    }

    public DecompilationRequestMessage(HitType typeofhit, BlockPos pos) {
        this.typeOfHit = typeofhit;
        this.pos = pos;
        this.entityOrItemId = -1;
    }

    public DecompilationRequestMessage(HitType typeOfHit, int entityId) {
        this.typeOfHit = typeOfHit;
        this.entityOrItemId = entityId;
        this.pos = new BlockPos(0, 0, 0);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.typeOfHit = HitType.values()[buf.readInt()];
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.entityOrItemId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.typeOfHit.ordinal());
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeInt(this.entityOrItemId);
    }

    @Override
    public IMessage onMessage(DecompilationRequestMessage message, MessageContext ctx) {
        EntityPlayerMP player = ctx.getServerHandler().player;

        if (System.currentTimeMillis() - COOLDOWN_MAP.getOrDefault(player.getUniqueID(), 0L) < 500)
            return null;
        COOLDOWN_MAP.put(player.getUniqueID(), System.currentTimeMillis());

        World world = player.world;

        switch (message.typeOfHit) {
            case BLOCK_ENTITY:
                sendToClient(world.getBlockState(message.pos).getBlock().getClass(), player);
                break;
            case TILE_ENTITY:
                TileEntity tileEntity = world.getTileEntity(message.pos);
                if (tileEntity != null) {
                    sendToClient(tileEntity.getClass(), player);
                } else {
                    TotalDebug.LOGGER.error("TileEntity is null");
                }
                break;
            case LIVING_ENTITY:
                Entity entity = world.getEntityByID(message.entityOrItemId);
                if (entity != null) {
                    sendToClient(entity.getClass(), player);
                } else {
                    TotalDebug.LOGGER.error("Entity is null");
                }
                break;
            case ITEM:
                Item item = Item.REGISTRY.getObjectById(message.entityOrItemId);
                if (item != null) {
                    if (item instanceof ItemBlock) {
                        sendToClient(((ItemBlock) item).getBlock().getClass(), player);
                    } else {
                        sendToClient(item.getClass(), player);
                    }
                } else {
                    TotalDebug.LOGGER.error("Item is null");
                }
        }
        return null;
    }

    public void sendToClient(Class<?> clazz, EntityPlayerMP player) {
        TotalDebug.INSTANCE.decompilationManager.getDecompiledFileContent(clazz).thenAccept(lines ->
                TotalDebug.INSTANCE.network.sendTo(new DecompilationResultMessage(clazz.getName(), lines), player));
    }
}
