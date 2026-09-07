package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

public class Range {

    private int offset;
    private int length;

    public Range(int offset, int length) {
        this.offset = offset;
        this.length = length;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public int getLength() {
        return length;
    }

    public int getOffset() {
        return offset;
    }

    public int getEndOffset() {
        return this.offset + this.length;
    }
}
