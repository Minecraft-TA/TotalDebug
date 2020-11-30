package com.github.minecraft_ta.totaldebug.fake;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;

public class FakeNetHandlerPlayServer extends NetHandlerPlayServer {

    public FakeNetHandlerPlayServer(MinecraftServer server, NetworkManager networkManagerIn, EntityPlayerMP playerIn) {
        super(server, networkManagerIn, playerIn);
    }

    @Override
    public void sendPacket(Packet<?> packetIn) {
        //NO OP
    }

    @Override
    public void disconnect(ITextComponent textComponent) {
        //NO OP
    }
}
