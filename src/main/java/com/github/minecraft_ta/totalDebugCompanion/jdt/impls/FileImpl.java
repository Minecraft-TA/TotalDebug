package com.github.minecraft_ta.totalDebugCompanion.jdt.impls;

import com.github.minecraft_ta.totalDebugCompanion.jdt.stubs.IFileStub;
import org.eclipse.jdt.core.IBuffer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class FileImpl implements IFileStub {

    private final String className;
    private final IBuffer contents;

    public FileImpl(String className, IBuffer contents) {
        this.className = className;
        this.contents = contents;
    }

    @Override
    public String getName() {
        return className.replace('.', '/') + ".java";
    }

    @Override
    public InputStream getContents() {
        return getContents(false);
    }

    @Override
    public InputStream getContents(boolean force) {
        return new ByteArrayInputStream(this.contents.getContents().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean exists() {
        return true;
    }
}
