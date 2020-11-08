package com.github.minecraft_ta.totaldebug.event;

import net.minecraft.network.Packet;
import net.minecraftforge.fml.common.eventhandler.Event;

public class PacketEvent extends Event {

    private Packet<?> packet;

    public PacketEvent(Packet<?> packet) {
        this.packet = packet;
    }

    public Packet<?> getPacket() {
        return packet;
    }

    public void setPacket(Packet<?> packet) {
        this.packet = packet;
    }

    public static class Incoming extends PacketEvent{

        public Incoming(Packet<?> packet) {
            super(packet);
        }
    }

    public static class Outgoing extends PacketEvent{

        public Outgoing(Packet<?> packet) {
            super(packet);
        }
    }


}
