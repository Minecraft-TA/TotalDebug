package com.github.minecraft_ta.totaldebug.client.script;

import com.github.minecraft_ta.totaldebug.network.ForwardedCompanionPayload;
import com.github.minecraft_ta.totaldebug.network.RunServerScriptPayload;
import com.github.minecraft_ta.totaldebug.network.StopServerScriptPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.network.PacketDistributor;

interface ServerScriptTransport {
    Availability availability();

    void run(RunServerScriptPayload payload);

    void stop(StopServerScriptPayload payload);

    record Availability(boolean available, String unavailableReason) {
        static Availability supported() {
            return new Availability(true, "");
        }

        static Availability unsupported(String reason) {
            return new Availability(false, reason);
        }
    }

    final class NeoForge implements ServerScriptTransport {
        @Override
        public Availability availability() {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            if (connection == null) {
                return Availability.unsupported("Join a world to run server-side scripts");
            }
            if (!connection.hasChannel(RunServerScriptPayload.TYPE)
                    || !connection.hasChannel(StopServerScriptPayload.TYPE)
                    || !connection.hasChannel(ForwardedCompanionPayload.TYPE)) {
                return Availability.unsupported("The current server does not support TotalDebug server-side scripts");
            }
            return Availability.supported();
        }

        @Override
        public void run(RunServerScriptPayload payload) {
            PacketDistributor.sendToServer(payload);
        }

        @Override
        public void stop(StopServerScriptPayload payload) {
            PacketDistributor.sendToServer(payload);
        }
    }
}
