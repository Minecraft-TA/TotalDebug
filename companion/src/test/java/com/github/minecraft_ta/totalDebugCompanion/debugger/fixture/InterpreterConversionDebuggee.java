package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

public final class InterpreterConversionDebuggee {
    public static Integer boxed = 7;
    public static Boolean flag = true;
    public static Integer absent;
    public static byte small = 3;
    public static long wide = 2L;
    public static Object text = "text";
    public static Object[] array = new String[]{"one", "two"};
    public static int calls;

    public static String choose(Object value) { return "object"; }
    public static String choose(String value) { return "string"; }
    public static String choose(int value) { return "int"; }
    public static String choose(long value) { return "long"; }
    public static String arguments(Object... values) { return values.length + ":" + values[0].getClass().getSimpleName(); }
    public static int effect() { calls++; return 1; }
    public static long wideEffect() { calls++; return 2L; }
    public static Object objectText() { return "text"; }
    public static String nullText() { return null; }

    public static void main(String[] args) {
        System.out.println(boxed); // CONVERSION_STOP
        System.out.println(flag);
    }
}
