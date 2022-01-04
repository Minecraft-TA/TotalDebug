package com.github.minecraft_ta.totaldebug.handler;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger.IncomingPacketsMessage;
import com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger.OutgoingPacketsMessage;
import com.github.tth05.scnet.Client;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import it.unimi.dsi.fastutil.bytes.Byte2ObjectMap;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.FMLIndexedMessageToMessageCodec;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleIndexedCodec;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

@ChannelHandler.Sharable
public class PacketLogger extends ChannelDuplexHandler {

    private final Map<String, Pair<Integer, Integer>> incomingPackets = new HashMap<>();
    private final Map<String, Pair<Integer, Integer>> outgoingPackets = new HashMap<>();
    private boolean incomingActive;
    private boolean outgoingActive;

    private Field discriminators;

    public PacketLogger() {
        try {
            discriminators = FMLIndexedMessageToMessageCodec.class.getDeclaredField("discriminators");
            discriminators.setAccessible(true);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
    }

    public void update() {
        final Client client = TotalDebug.PROXY.getCompanionApp().getClient();
        if (incomingActive) {
            client.getMessageProcessor().enqueueMessage(new IncomingPacketsMessage(incomingPackets));
        }
        if (outgoingActive) {
            client.getMessageProcessor().enqueueMessage(new OutgoingPacketsMessage(outgoingPackets));
        }
    }

    public void setIncomingActive(boolean incomingActive) {
        this.incomingActive = incomingActive;
    }

    public void setOutgoingActive(boolean outgoingActive) {
        this.outgoingActive = outgoingActive;
    }

    public void clear() {
        incomingPackets.clear();
        outgoingPackets.clear();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (incomingActive && msg instanceof Packet) {
                if (msg instanceof FMLProxyPacket) {
                    FMLProxyPacket fmlPacket = (FMLProxyPacket) msg;
                    SimpleIndexedCodec codec = NetworkRegistry.INSTANCE.getChannel(fmlPacket.channel(), Side.CLIENT).pipeline().get(SimpleIndexedCodec.class);
                    if (codec != null) {
                        ByteBuf payload = fmlPacket.payload();
                        payload.markReaderIndex();
                        byte discriminator = payload.readByte();
                        Class<? extends IMessage> clazz = ((Byte2ObjectMap<Class<? extends IMessage>>) discriminators.get(codec)).get(discriminator);
                        incomingPackets.merge(clazz.getName(), Pair.of(1, payload.readableBytes()), (pair, pair2) -> Pair.of(pair.getLeft() + pair2.getLeft(), pair.getRight() + pair2.getRight()));
                        payload.resetReaderIndex();
                    }
                } else {
                    incomingPackets.merge(msg.getClass().getName(), Pair.of(1, getPacketSize(msg)), (pair, pair2) -> Pair.of(pair.getLeft() + pair2.getLeft(), pair.getRight() + pair2.getRight()));
                }
            }

        }
        catch (Exception e) {
            e.printStackTrace();
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        try {
            if (outgoingActive && msg instanceof Packet) {
                if (msg instanceof FMLProxyPacket) {
                    FMLProxyPacket fmlPacket = (FMLProxyPacket) msg;
                    SimpleIndexedCodec codec = NetworkRegistry.INSTANCE.getChannel(fmlPacket.channel(), Side.CLIENT).pipeline().get(SimpleIndexedCodec.class);
                    if (codec != null) {
                        ByteBuf payload = fmlPacket.payload();
                        payload.markReaderIndex();
                        byte discriminator = payload.readByte();
                        Class<? extends IMessage> clazz = ((Byte2ObjectMap<Class<? extends IMessage>>) discriminators.get(codec)).get(discriminator);
                        outgoingPackets.merge(clazz.getName(), Pair.of(1, payload.readableBytes()), (pair, pair2) -> Pair.of(pair.getLeft() + pair2.getLeft(), pair.getRight() + pair2.getRight()));
                        payload.resetReaderIndex();
                    }
                } else {
                    outgoingPackets.merge(msg.getClass().getName(), Pair.of(1, getPacketSize(msg)), (pair, pair2) -> Pair.of(pair.getLeft() + pair2.getLeft(), pair.getRight() + pair2.getRight()));
                }
            }

        }
        catch (Exception e) {
            e.printStackTrace();
        }
        super.write(ctx, msg, promise);
    }

    private int getPacketSize(Object msg) throws IOException {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        ((Packet<?>) msg).writePacketData(buf);
        return buf.readableBytes();
    }

}
