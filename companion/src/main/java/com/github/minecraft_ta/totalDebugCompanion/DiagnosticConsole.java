package com.github.minecraft_ta.totalDebugCompanion;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Writes diagnostics to the launch log and, for direct launches, the original console channel. */
final class DiagnosticConsole {
    private DiagnosticConsole() {
    }

    /** The caller owns both destinations; closing the returned stream only flushes them. */
    static PrintStream stream(OutputStream log, PrintStream console) {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                if (console != null) console.write(value);
                log.write(value);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                if (console != null) console.write(bytes, offset, length);
                log.write(bytes, offset, length);
            }

            @Override
            public void flush() throws IOException {
                if (console != null) console.flush();
                log.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }
}
