package com.github.minecraft_ta.totaldebug.util.compiler;

import javax.tools.SimpleJavaFileObject;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public class StringInputObject extends SimpleJavaFileObject {

    private final String sourceCode;

    protected StringInputObject(String className, String sourceCode) throws URISyntaxException {
        super(new URI("string:///" + className + Kind.SOURCE.extension), Kind.SOURCE);
        this.sourceCode = sourceCode;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
        return this.sourceCode;
    }
}
