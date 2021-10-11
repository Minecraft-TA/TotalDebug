package com.github.minecraft_ta.totaldebug.util.compiler;

import javax.tools.SimpleJavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;

public class BytecodeOutputObject extends SimpleJavaFileObject {

    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    protected BytecodeOutputObject(String className) throws URISyntaxException {
        super(new URI(className), Kind.CLASS);
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
        return this.outputStream;
    }

    public byte[] getByteCode() {
        return this.outputStream.toByteArray();
    }
}
