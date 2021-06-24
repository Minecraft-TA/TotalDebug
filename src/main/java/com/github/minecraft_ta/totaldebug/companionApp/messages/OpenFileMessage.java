package com.github.minecraft_ta.totaldebug.companionApp.messages;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.nio.file.Path;

public class OpenFileMessage extends AbstractMessageOutgoing {

    private final Path path;
    private final int row;

    public OpenFileMessage(Path path, int row) {
        this.path = path;
        this.row = row;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeString(this.path.toAbsolutePath().toString());
        messageStream.writeInt(this.row);
    }
}
