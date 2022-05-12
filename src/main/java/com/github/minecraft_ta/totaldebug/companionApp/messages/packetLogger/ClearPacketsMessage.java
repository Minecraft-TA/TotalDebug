package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public class ClearPacketsMessage extends AbstractMessageIncoming {

    @Override
    public void read(ByteBufferInputStream messageStream) {
        //Nothing to read
    }

    public static void handle(ClearPacketsMessage message) {
        TotalDebug.PROXY.getPackerLogger().clear();
    }
}
