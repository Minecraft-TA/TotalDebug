package com.github.minecraft_ta.totaldebug.companionApp.messages;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public class DecompileAndOpenRequestMessage extends AbstractMessageIncoming {

    private String className;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.className = messageStream.readString();
    }

    public static void handle(DecompileAndOpenRequestMessage message) {
        try {
            TotalDebug.PROXY.getDecompilationManager().openGui(Class.forName(message.className));
        } catch (Throwable t) {
            TotalDebug.LOGGER.error("Received decompile request message for unknown class", t);
        }
    }
}
