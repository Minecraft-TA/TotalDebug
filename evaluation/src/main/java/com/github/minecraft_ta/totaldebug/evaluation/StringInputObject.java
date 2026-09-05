package com.github.minecraft_ta.totaldebug.evaluation;

import javax.tools.SimpleJavaFileObject;
import java.net.URI;

final class StringInputObject extends SimpleJavaFileObject {
    private final String sourceCode;

    StringInputObject(String binaryName, String sourceCode) {
        super(URI.create("string:///" + binaryName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
        this.sourceCode = sourceCode;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return this.sourceCode;
    }
}
