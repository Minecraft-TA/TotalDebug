package com.github.minecraft_ta.totaldebug.companionApp.messages;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Collection;

public class OpenSearchResultsMessage extends AbstractMessageOutgoing {

    private final String query;
    private final Collection<String> results;
    private final boolean methodSearch;
    private final int classesCount;
    private final int time;

    public OpenSearchResultsMessage(String query, Collection<String> results, boolean methodSearch, int classesCount, int time) {
        this.query = query;
        this.results = results;
        this.methodSearch = methodSearch;
        this.classesCount = classesCount;
        this.time = time;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeString(this.query);
        messageStream.writeInt(this.results.size());
        results.forEach(messageStream::writeString);
        messageStream.writeBoolean(this.methodSearch);
        messageStream.writeInt(this.classesCount);
        messageStream.writeInt(this.time);
    }
}
