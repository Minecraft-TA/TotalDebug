package com.github.minecraft_ta.totaldebug.protocol;

import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Explicit local activation before attaching the single-game transport. */
public final class ProjectSelectionRequest {
    public static final String PATH = "/select-project";
    public static final int MAX_BYTES = 32_768;

    private ProjectSelectionRequest() {}

    public static byte[] encode(ClientHelloMessage hello) {
        var output = new ByteBufferOutputStream(256, MAX_BYTES, 8192);
        hello.write(output);
        ByteBuffer buffer = output.getBuffer();
        byte[] bytes = new byte[buffer.position()];
        buffer.flip();
        buffer.get(bytes);
        return bytes;
    }

    public static ClientHelloMessage decode(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Project request is too large");
        var buffer = ByteBuffer.wrap(bytes);
        var hello = new ClientHelloMessage();
        hello.read(new ByteBufferInputStream(buffer, 8192));
        if (buffer.hasRemaining()) throw new IllegalArgumentException("Trailing project request data");
        return hello;
    }

    public static void send(int port, ClientHelloMessage hello) throws IOException {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid project request port");
        var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + PATH).toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setDoOutput(true);
        byte[] bytes = encode(hello);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            try (var output = connection.getOutputStream()) { output.write(bytes); }
            if (connection.getResponseCode() != 204) {
                String detail = "Companion rejected project selection";
                if (connection.getErrorStream() != null) {
                    try (var input = connection.getErrorStream()) {
                        detail = new String(input.readNBytes(4096), StandardCharsets.UTF_8);
                    }
                }
                throw new IOException(detail);
            }
        } finally { connection.disconnect(); }
    }
}
