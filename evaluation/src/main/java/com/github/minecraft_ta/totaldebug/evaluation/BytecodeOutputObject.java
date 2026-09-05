package com.github.minecraft_ta.totaldebug.evaluation;

import javax.tools.SimpleJavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;

final class BytecodeOutputObject extends SimpleJavaFileObject {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    BytecodeOutputObject(String binaryName, Kind kind) {
        super(URI.create("memory:///" + binaryName.replace('.', '/') + kind.extension), kind);
    }

    @Override
    public OutputStream openOutputStream() {
        return this.output;
    }

    byte[] bytecode() {
        return this.output.toByteArray();
    }
}
