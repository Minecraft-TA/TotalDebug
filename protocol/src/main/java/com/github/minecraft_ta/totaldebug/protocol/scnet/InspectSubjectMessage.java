package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.Objects;

/** Sent by the mod when the user selects a block or entity to inspect. */
public final class InspectSubjectMessage extends AbstractMessage {
    private InspectSubjectPayload payload;

    public InspectSubjectMessage() {
    }

    public InspectSubjectMessage(InspectSubjectPayload payload) {
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = InspectSubjectPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public InspectSubjectPayload payload() {
        return this.payload;
    }
}
