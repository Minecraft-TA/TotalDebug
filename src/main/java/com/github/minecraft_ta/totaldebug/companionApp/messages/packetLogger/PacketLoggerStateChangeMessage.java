package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public class PacketLoggerStateChangeMessage extends AbstractMessageIncoming {

    private boolean logIncoming;
    private boolean logOutgoing;

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
}
