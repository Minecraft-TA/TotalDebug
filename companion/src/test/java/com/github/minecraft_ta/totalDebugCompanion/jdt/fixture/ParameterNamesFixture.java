package com.github.minecraft_ta.totalDebugCompanion.jdt.fixture;

public class ParameterNamesFixture<T> {
    public ParameterNamesFixture(T initialValue) { }
    public T convert(T inputValue) { return inputValue; }
    public void generated(String arg0, int arg1, String s) { }
    public void wide(long ticks, double ratio, String label) { }
    public void varargs(String... entries) { }
    public void unicode(String 名前, int $count) { }

    public class Inner {
        public Inner(String title) { }
    }

    public class GenericInner {
        public GenericInner(T content) { }
    }
}
