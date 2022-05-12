package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public class PacketLoggerStateChangeMessage extends AbstractMessageIncoming {

    private boolean logIncoming;
    private boolean logOutgoing;

    public PacketLoggerStateChangeMessage() {
    }

    public PacketLoggerStateChangeMessage(boolean logIncoming, boolean logOutgoing) {
        this.logIncoming = logIncoming;
        this.logOutgoing = logOutgoing;
    }

    @Override
    public void read(ByteBufferInputStream messageStream) {
        logIncoming = messageStream.readBoolean();
        logOutgoing = messageStream.readBoolean();
    }

    public boolean isLogIncoming() {
        return logIncoming;
    }

    public boolean isLogOutgoing() {
        return logOutgoing;
    }

    public static void handle(PacketLoggerStateChangeMessage message) {
        TotalDebug.PROXY.getPackerLogger().setIncomingActive(message.isLogIncoming());
        TotalDebug.PROXY.getPackerLogger().setOutgoingActive(message.isLogOutgoing());
    }
}
