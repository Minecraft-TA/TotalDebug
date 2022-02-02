package com.github.minecraft_ta.totaldebug.handler;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger.IncomingPacketsMessage;
import com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger.OutgoingPacketsMessage;
import com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger.PacketContentMessage;
import com.github.minecraft_ta.totaldebug.util.ObjectToNbtHelper;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@ChannelHandler.Sharable
public class PacketLogger extends ChannelDuplexHandler {

    private final Map<String, Pair<Integer, Integer>> incomingPackets = new HashMap<>();
    private final Map<String, Pair<Integer, Integer>> outgoingPackets = new HashMap<>();
    private final Set<String> packetsToCapture = new HashSet<>();
    private final Client client = TotalDebug.PROXY.getCompanionApp().getClient();
    private boolean incomingActive;
    private boolean outgoingActive;
    private String activeChannel;

    private Field discriminators;

    public PacketLogger() {
        this.activeChannel = "All channels";
        try {
            discriminators = FMLIndexedMessageToMessageCodec.class.getDeclaredField("discriminators");
            discriminators.setAccessible(true);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
    }

    public void update() {
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
        packetsToCapture.clear();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (incomingActive) {
            logPacket(msg, incomingPackets);
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (outgoingActive) {
            logPacket(msg, outgoingPackets);
        }
        super.write(ctx, msg, promise);
    }

    private void logPacket(Object msg, Map<String, Pair<Integer, Integer>> packetMap) {
        try {
            if (msg instanceof Packet) {
                if (msg instanceof FMLProxyPacket) {
                    FMLProxyPacket fmlPacket = (FMLProxyPacket) msg;
                    String channel = fmlPacket.channel();
                    if (!activeChannel.equals("All channels") && !channel.equals(activeChannel)) return;

                    ByteBuf payload = fmlPacket.payload();

                    //Handles jei packets differently because they send the packets themselves
                    if (channel.equals("jei")) {
                        handleJeiPacket(payload, packetMap);
                        return;
                    }

                    SimpleIndexedCodec codec = NetworkRegistry.INSTANCE.getChannel(channel, Side.CLIENT).pipeline().get(SimpleIndexedCodec.class);
                    if (codec != null) {
                        payload.markReaderIndex();
                        byte discriminator = payload.readByte();
                        //noinspection unchecked
                        Class<? extends IMessage> clazz = ((Byte2ObjectMap<Class<? extends IMessage>>) discriminators.get(codec)).get(discriminator);
                        putPacket(clazz, payload.readableBytes(), packetMap, payload);
                        payload.resetReaderIndex();
                    } else {
                        putPacket(fmlPacket.getClass(), payload.readableBytes(), packetMap, null);
                    }
                } else {
                    if (activeChannel.equals("All channels") || activeChannel.equals("minecraft")) {
                        putPacket(msg.getClass(), getPacketSize(msg), packetMap, msg);
                    }
                }
            }
        } catch (Exception e) {
            TotalDebug.LOGGER.error("Error logging packet", e);
        }
    }

    /**
     * Merges the packet into the map, or creates a new entry if it doesn't exist
     *
     * @param clazz     The class of the packet
     * @param size      The size of the packet in bytes
     * @param packetMap The map to merge the packet into
     */
    private void putPacket(Class<?> clazz, int size, Map<String, Pair<Integer, Integer>> packetMap, Object payloadOrPacket) {
        if (packetsToCapture.contains(clazz.getName())) {
            if (payloadOrPacket instanceof ByteBuf) {
                try {
                    Object packetObject = clazz.newInstance();
                    if (packetObject instanceof IMessage) {
                        ((IMessage) packetObject).fromBytes((ByteBuf) payloadOrPacket);
                        client.getMessageProcessor().enqueueMessage(new PacketContentMessage(clazz.getName(), ObjectToNbtHelper.objectToNbt(packetObject)));
                    }
                } catch (InstantiationException | IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
            if (payloadOrPacket instanceof Packet) {
                client.getMessageProcessor().enqueueMessage(new PacketContentMessage(clazz.getName(), ObjectToNbtHelper.objectToNbt(payloadOrPacket)));
            }
        }
        packetMap.merge(clazz.getName(), Pair.of(1, size), (pair, pair2) -> Pair.of(pair.getLeft() + pair2.getLeft(), pair.getRight() + pair2.getRight()));
    }

    /**
     * Handles the packet sent by jei and adds it to the map.
     * It's not possible to get directly because jei uses method references
     *
     * @param payload   The packet payload
     * @param packetMap The map to merge the packet into
     */
    private void handleJeiPacket(ByteBuf payload, Map<String, Pair<Integer, Integer>> packetMap) {
        payload.markReaderIndex();
        int packetId = payload.readByte();

        Class<?> clazz = null;
        try {
            switch (packetId) {
                case 0:
                    if (outgoingActive) clazz = Class.forName("mezz.jei.network.packets.PacketRecipeTransfer");
                    else clazz = Class.forName("mezz.jei.network.packets.PacketCheatPermission");
                    break;
                case 1:
                    clazz = Class.forName("mezz.jei.network.packets.PacketDeletePlayerItem");
                    break;
                case 2:
                    clazz = Class.forName("mezz.jei.network.packets.PacketGiveItemStack");
                    break;
                case 3:
                    clazz = Class.forName("mezz.jei.network.packets.PacketSetHotbarItemStack");
                    break;
                case 4:
                    clazz = Class.forName("mezz.jei.network.packets.PacketRequestCheatPermission");
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        if (clazz != null) {
            putPacket(clazz, payload.readableBytes(), packetMap, payload);
        }
        payload.resetReaderIndex();
    }

    /**
     * Gets the size of the packet in bytes by writing it to a buffer and reading the size
     */
    private int getPacketSize(Object msg) throws IOException {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        try {
            ((Packet<?>) msg).writePacketData(buf);
        } catch (NullPointerException ignored) {
        }
        return buf.readableBytes();
    }

    public void setChannel(String channel) {
        this.activeChannel = channel;
    }

    public void setPacketsToCapture(String packet, boolean remove) {
        if (remove) {
            packetsToCapture.remove(packet);
        } else {
            packetsToCapture.add(packet);
        }
    }
}
