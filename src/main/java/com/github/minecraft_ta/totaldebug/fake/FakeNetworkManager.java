package com.github.minecraft_ta.totaldebug.fake;

import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;

public class FakeNetworkManager extends NetworkManager {

    public FakeNetworkManager(EnumPacketDirection packetDirection) {
        super(packetDirection);
    }

    @Override
    public void disableAutoRead() {
        //NO OP
    }

    @Override
    public void handleDisconnection() {
        //NO OP
    }
}


