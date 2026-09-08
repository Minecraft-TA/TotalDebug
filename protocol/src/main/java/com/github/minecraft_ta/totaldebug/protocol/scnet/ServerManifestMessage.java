package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded, ordered chunks. An empty transfer clears the previous server session. */
public final class ServerManifestMessage extends AbstractMessage {
    public static final int CHUNK_BYTES = 512 * 1024;
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private String sessionId;
    private String detail;
    private int offset;
    private int total;
    private byte[] bytes;

    public ServerManifestMessage() {}

    public ServerManifestMessage(String sessionId, String detail, int offset, int total, byte[] bytes) {
        if (sessionId.length() > 64 || detail.length() > 2048 || total < 0 || total > MAX_BYTES
                || offset < 0 || offset > total || bytes.length > CHUNK_BYTES
                || (long) offset + bytes.length > total || (total > 0 && (sessionId.isBlank() || bytes.length == 0))) {
            throw new IllegalArgumentException("Invalid server manifest chunk");
        }
        this.sessionId = sessionId;
        this.detail = detail;
        this.offset = offset;
        this.total = total;
        this.bytes = bytes.clone();
    }

    public static ServerManifestMessage unavailable(String detail) {
        return new ServerManifestMessage("", detail, 0, 0, new byte[0]);
    }

    public static List<ServerManifestMessage> split(String sessionId, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Invalid manifest size");
        var messages = new ArrayList<ServerManifestMessage>();
        for (int offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
            messages.add(new ServerManifestMessage(sessionId, "", offset, bytes.length,
                    Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + CHUNK_BYTES))));
        }
        return List.copyOf(messages);
    }

    @Override
    public void read(ByteBufferInputStream input) {
        String session = input.readString();
        String detail = input.readString();
        int offset = input.readInt();
        int total = input.readInt();
        int length = input.readInt();
        if (length < 0 || length > CHUNK_BYTES) throw new IllegalArgumentException("Invalid manifest chunk size");
        var checked = new ServerManifestMessage(session, detail, offset, total, input.readByteArray(length));
        this.sessionId = checked.sessionId;
        this.detail = checked.detail;
        this.offset = checked.offset;
        this.total = checked.total;
        this.bytes = checked.bytes;
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        output.writeString(sessionId);
        output.writeString(detail);
        output.writeInt(offset);
        output.writeInt(total);
        output.writeInt(bytes.length);
        output.writeByteArray(bytes);
    }

    public String sessionId() { return sessionId; }
    public String detail() { return detail; }
    public int offset() { return offset; }
    public int total() { return total; }
    public byte[] bytes() { return bytes.clone(); }

    public static final class Assembler {
        private String session = "";
        private int total;
        private ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public byte[] accept(ServerManifestMessage message) {
            if (message.total == 0) { clear(); return null; }
            if (message.offset == 0) {
                clear();
                this.session = message.sessionId;
                this.total = message.total;
            }
            if (!this.session.equals(message.sessionId) || this.total != message.total || buffer.size() != message.offset) {
                clear();
                throw new IllegalArgumentException("Out-of-order server manifest transfer");
            }
            buffer.writeBytes(message.bytes);
            if (buffer.size() != total) return null;
            byte[] result = buffer.toByteArray();
            clear();
            return result;
        }

        public void clear() {
            session = "";
            total = 0;
            buffer = new ByteArrayOutputStream();
        }
    }
}
