package com.github.minecraft_ta.totalDebugCompanion.jdt.impls;

import com.github.minecraft_ta.totalDebugCompanion.jdt.stubs.IBufferStub;
import org.eclipse.jdt.core.compiler.CharOperation;

public class BufferImpl implements IBufferStub {

    private final char[] s;

    public BufferImpl(String s) {
        this.s = s.toCharArray();
    }

    @Override
    public char getChar(int position) {
        return this.s[position];
    }

    @Override
    public char[] getCharacters() {
        return this.s;
    }

    @Override
    public String getContents() {
        return new String(this.s);
    }

    @Override
    public int getLength() {
        return this.s.length;
    }

    @Override
    public String getText(int offset, int length) throws IndexOutOfBoundsException {
        return new String(CharOperation.subarray(this.s, offset, offset + length));
    }
}
