package com.github.minecraft_ta.totaldebug.handler;

import cpw.mods.fml.common.network.FMLIndexedMessageToMessageCodec;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleIndexedCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import it.unimi.dsi.fastutil.bytes.Byte2ObjectMap;
import net.minecraftforge.fml.relauncher.Side;

import java.lang.reflect.Field;
import java.util.HashSet;

@ChannelHandler.Sharable
public class PacketBlocker extends ChannelDuplexHandler {

    private final HashSet<Class<?>> blockedPackets = new HashSet<>();
    private Field discriminators;

    public PacketBlocker() {
        try {
            discriminators = FMLIndexedMessageToMessageCodec.class.getDeclaredField("discriminators");
            discriminators.setAccessible(true);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!blockedPackets.isEmpty()) {
            if (msg instanceof FMLProxyPacket) {
                FMLProxyPacket packet = (FMLProxyPacket) msg;
                ByteBuf payload = packet.payload();
                SimpleIndexedCodec codec = NetworkRegistry.INSTANCE.getChannel(packet.channel(), Side.CLIENT).pipeline().get(SimpleIndexedCodec.class);
                if (codec != null) {
                    payload.markReaderIndex();
                    byte discriminator = payload.readByte();
                    //noinspection unchecked
                    Class<? extends IMessage> clazz = ((Byte2ObjectMap<Class<? extends IMessage>>) discriminators.get(codec)).get(discriminator);
                    payload.resetReaderIndex();
                    if (blockedPackets.contains(clazz)) {
                        return;
                    }
                }
            } else if (blockedPackets.contains(msg.getClass())) {
                return;
            }
        }
        super.write(ctx, msg, promise);
    }

    public void addBlockedPacket(Class<?> clazz) {
        blockedPackets.add(clazz);
    }

    public void clearBlockedPackets() {
        blockedPackets.clear();
    }

}
