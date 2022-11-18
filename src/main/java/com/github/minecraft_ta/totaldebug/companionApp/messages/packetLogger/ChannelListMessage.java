package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.relauncher.Side;

import java.util.Set;

public class ChannelListMessage extends AbstractMessage {

    @Override
    public void read(ByteBufferInputStream messageStream) {
        //Nothing to read
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        Set<String> channels = NetworkRegistry.INSTANCE.channelNamesFor(Side.CLIENT);
        messageStream.writeInt(channels.size());
        channels.forEach(messageStream::writeString);
    }
}
